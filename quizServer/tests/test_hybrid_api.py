import base64
import os
import tempfile
import time
import uuid
from pathlib import Path
from urllib.parse import urlencode

# The application reads DATABASE_URL while importing app.database. Set the
# test database before importing the FastAPI app so the developer quiz.db is
# never touched by the suite.
TEST_DATABASE = Path(tempfile.gettempdir()) / f"quiz-api-tests-{uuid.uuid4().hex}.db"
os.environ["DATABASE_URL"] = f"sqlite:///{TEST_DATABASE.as_posix()}"
os.environ["QUIZ_AUTH_SECRET"] = "hybrid-api-test-secret"
os.environ["QUIZ_RESET_DB_ON_STARTUP"] = "1"
os.environ["QUIZ_ENABLE_TEST_TOPUPS"] = "1"

import pytest
from fastapi.testclient import TestClient
from sqlalchemy import func, select
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.asymmetric import ec

from app.database import SessionLocal
from app.main import app
import app.main as main_module
from app.models import CurrencyTransaction, QuizSession


@pytest.fixture(scope="module")
def client():
    with TestClient(app) as test_client:
        yield test_client


def auth_headers(client: TestClient, suffix: str) -> dict[str, str]:
    response = client.post(
        "/api/v1/auth/anonymous",
        json={"device_id": f"hybrid-test-{suffix}-{uuid.uuid4().hex[:8]}"},
    )
    assert response.status_code == 200, response.text
    return {"Authorization": f"Bearer {response.json()['access_token']}"}


def topic_id(client: TestClient, headers: dict[str, str]) -> int:
    response = client.get("/api/v1/bootstrap", headers=headers)
    assert response.status_code == 200, response.text
    return response.json()["topics"][0]["id"]


def balance(client: TestClient, headers: dict[str, str]) -> int:
    response = client.get("/api/v1/me/balance", headers=headers)
    assert response.status_code == 200, response.text
    return response.json()["balance"]


def offline_payload(topic: int, session_id: str, **overrides) -> dict:
    payload = {
        "client_session_id": session_id,
        "topic_id": topic,
        "mode": "solo",
        "difficulty": "common",
        "stake": 100,
        "total_questions": 15,
        "correct_answers": 0,
        "cancelled": True,
    }
    payload.update(overrides)
    return payload


def test_bootstrap_contains_all_localizable_content(client):
    headers = auth_headers(client, "catalog")
    response = client.get(
        "/api/v1/bootstrap",
        headers={**headers, "Accept-Language": "ru"},
    )
    assert response.status_code == 200, response.text
    payload = response.json()

    assert {item["language_code"] for item in payload["topic_texts"]} == {"en", "ru", "uk"}
    assert {item["language_code"] for item in payload["question_texts"]} == {"en", "ru", "uk"}
    assert {item["language_code"] for item in payload["option_texts"]} == {"en", "ru", "uk"}


def test_offline_sync_is_idempotent_and_does_not_charge_twice(client):
    headers = auth_headers(client, "offline-idempotency")
    topic = topic_id(client, headers)
    before = balance(client, headers)
    payload = offline_payload(topic, f"offline-{uuid.uuid4().hex}")

    first = client.post("/api/v1/me/quiz-sessions/offline-sync", json=payload, headers=headers)
    second = client.post("/api/v1/me/quiz-sessions/offline-sync", json=payload, headers=headers)
    assert first.status_code == 200, first.text
    assert second.status_code == 200, second.text
    assert first.json()["session_id"] == second.json()["session_id"]
    assert first.json()["balance"] == before - 100
    assert second.json()["balance"] == before - 100

    with SessionLocal() as db:
        stored_session = db.scalar(
            select(QuizSession).where(
                QuizSession.client_session_id == payload["client_session_id"]
            )
        )
        assert stored_session is not None
        stake_count = db.scalar(
            select(func.count(CurrencyTransaction.id)).where(
                CurrencyTransaction.reason == f"offline_quiz_stake:{stored_session.id}",
                CurrencyTransaction.user_id == stored_session.user_id,
            )
        )
    assert stake_count == 1


def test_offline_result_and_two_topup_sources_are_independent(client):
    headers = auth_headers(client, "topup-sources")
    topic = topic_id(client, headers)
    before = balance(client, headers)
    session_id = f"offline-reward-{uuid.uuid4().hex}"
    sync = client.post(
        "/api/v1/me/quiz-sessions/offline-sync",
        json=offline_payload(
            topic,
            session_id,
            cancelled=False,
            correct_answers=15,
        ),
        headers=headers,
    )
    assert sync.status_code == 200, sync.text
    # Common difficulty: 100 stake, 2x multiplier, perfect score => 200 reward.
    assert sync.json()["balance"] == before + 100

    test_operation = f"test-topup-{uuid.uuid4().hex}"
    ad_operation = f"ad-topup-{uuid.uuid4().hex}"
    test_topup = client.post(
        "/api/v1/me/currency/top-up",
        json={"amount": 1000, "source": "test", "operation_id": test_operation},
        headers=headers,
    )
    test_retry = client.post(
        "/api/v1/me/currency/top-up",
        json={"amount": 1000, "source": "test", "operation_id": test_operation},
        headers=headers,
    )
    ad_topup = client.post(
        "/api/v1/me/currency/top-up",
        json={"amount": 1000, "source": "ad_test", "operation_id": ad_operation},
        headers=headers,
    )
    ad_retry = client.post(
        "/api/v1/me/currency/top-up",
        json={"amount": 1000, "source": "ad_test", "operation_id": ad_operation},
        headers=headers,
    )
    assert test_topup.status_code == 200, test_topup.text
    assert test_retry.status_code == 200, test_retry.text
    assert ad_topup.status_code == 200, ad_topup.text
    assert ad_retry.status_code == 200, ad_retry.text
    assert test_retry.json()["balance"] == test_topup.json()["balance"]
    assert ad_retry.json()["balance"] == ad_topup.json()["balance"]
    assert ad_topup.json()["balance"] == before + 2100

    with SessionLocal() as db:
        reasons = db.scalars(
            select(CurrencyTransaction.reason).where(
                CurrencyTransaction.client_operation_id.in_([test_operation, ad_operation])
            )
        ).all()
    assert reasons.count("currency_top_up:test") == 1
    assert reasons.count("currency_top_up:ad_test") == 1

    conflict = client.post(
        "/api/v1/me/currency/top-up",
        json={"amount": 1000, "source": "ad_test", "operation_id": test_operation},
        headers=headers,
    )
    assert conflict.status_code == 409


def test_online_cancel_and_offline_sync_both_keep_the_stake(client):
    headers = auth_headers(client, "hybrid-stake")
    topic = topic_id(client, headers)
    before = balance(client, headers)

    online = client.post(
        "/api/v1/me/quiz-sessions/start",
        json={
            "topic_id": topic,
            "mode": "computer",
            "difficulty": "basic",
            "stake": 100,
            "total_questions": 10,
            "client_session_id": f"online-{uuid.uuid4().hex}",
        },
        headers=headers,
    )
    assert online.status_code == 200, online.text
    assert online.json()["balance"] == before - 100

    cancelled = client.post(
        f"/api/v1/me/quiz-sessions/{online.json()['session_id']}/cancel",
        headers=headers,
    )
    assert cancelled.status_code == 200, cancelled.text
    assert cancelled.json()["balance"] == before - 100

    offline = client.post(
        "/api/v1/me/quiz-sessions/offline-sync",
        json=offline_payload(topic, f"offline-cancel-{uuid.uuid4().hex}"),
        headers=headers,
    )
    assert offline.status_code == 200, offline.text
    assert offline.json()["balance"] == before - 200


def test_monetization_endpoints_require_server_side_verification(client, monkeypatch):
    headers = auth_headers(client, "monetization-validation")

    monkeypatch.setenv("QUIZ_ENABLE_TEST_TOPUPS", "0")
    disabled_test_topup = client.post(
        "/api/v1/me/currency/top-up",
        json={"amount": 1000, "source": "test", "operation_id": f"disabled-{uuid.uuid4().hex}"},
        headers=headers,
    )
    assert disabled_test_topup.status_code == 403
    monkeypatch.setenv("QUIZ_ENABLE_TEST_TOPUPS", "1")

    unknown_product = client.post(
        "/api/v1/me/store/google-play/verify",
        json={"product_id": "coins_unknown", "purchase_token": "token"},
        headers=headers,
    )
    assert unknown_product.status_code == 400

    # A valid product still cannot credit coins without the Play service
    # account configured on the backend.
    missing_credentials = client.post(
        "/api/v1/me/store/google-play/verify",
        json={"product_id": "coins_55", "purchase_token": "token"},
        headers=headers,
    )
    assert missing_credentials.status_code == 503

    invalid_ssv = client.get(
        "/api/v1/ads/rewarded/ssv?transaction_id=not-signed",
    )
    assert invalid_ssv.status_code == 400


def test_verified_rewarded_ssv_credits_once(client, monkeypatch):
    auth = client.post(
        "/api/v1/auth/anonymous",
        json={"device_id": f"ssv-test-{uuid.uuid4().hex}"},
    )
    assert auth.status_code == 200, auth.text
    payload = auth.json()
    headers = {"Authorization": f"Bearer {payload['access_token']}"}
    before = balance(client, headers)

    private_key = ec.generate_private_key(ec.SECP256R1())
    key_id = 987654
    monkeypatch.setattr(
        main_module,
        "_admob_keys_cache",
        (time.time() + 3600, {key_id: private_key.public_key()}),
    )
    signed_query = urlencode(
        {
            "ad_network": "TEST",
            "transaction_id": f"ssv-{uuid.uuid4().hex}",
            "user_id": "",
            "custom_data": payload["user_id"],
            "reward_amount": "1000",
            "reward_item": "coins",
        }
    )
    signature = base64.urlsafe_b64encode(
        private_key.sign(signed_query.encode("utf-8"), ec.ECDSA(hashes.SHA256()))
    ).decode("ascii")
    callback_url = (
        "/api/v1/ads/rewarded/ssv?"
        + signed_query
        + f"&signature={signature}&key_id={key_id}"
    )

    first = client.get(callback_url)
    second = client.get(callback_url)
    assert first.status_code == 200, first.text
    assert second.status_code == 200, second.text
    assert first.json()["status"] == "ok"
    assert second.json()["status"] == "already_processed"
    assert balance(client, headers) == before + 1000

import base64
import json
import logging
import os
import time
import uuid
from urllib.parse import parse_qs, urlencode, urlsplit
from urllib.error import HTTPError, URLError
from urllib.request import Request as UrlRequest, urlopen

from fastapi import Depends, FastAPI, Header, HTTPException, Request, status
from fastapi.middleware.cors import CORSMiddleware
from sqlalchemy import select
from sqlalchemy.orm import Session

from app.auth import issue_token, require_user
from app.database import Base, SessionLocal, engine
from app.migrations import run_compatibility_migrations
from app.models import AdRewardTransaction, Asset, CurrencyTransaction, GooglePlayPurchase, QuizSession, Topic, User, UserAsset
from app.schemas import (
    AnonymousAuthRequest,
    AuthResponse,
    BootstrapResponse,
    FinishQuizRequest,
    GooglePlayPurchaseRequest,
    OfflineQuizSyncRequest,
    PlayGamesLinkRequest,
    PurchaseAssetRequest,
    QuizActionResponse,
    StartQuizRequest,
    TopUpCurrencyRequest,
)
from app.seed import build_bootstrap_payload, seed_database


app = FastAPI(title="Quiz Server", version="1.1.0")

GOOGLE_PLAY_PRODUCTS = {
    "coins_55": 55,
    "coins_165": 165,
    "coins_560": 560,
    "coins_1900": 1900,
    "coins_6800": 6800,
}
AD_REWARD_AMOUNT = 1000
AD_REWARD_ITEM = "coins"
AD_SSV_KEYS_URL = "https://www.gstatic.com/admob/reward/verifier-keys.json"
GOOGLE_PLAY_TOKEN_URL = "https://oauth2.googleapis.com/token"
GOOGLE_PLAY_PLAYER_URL = "https://games.googleapis.com/games/v1/players/me"
GOOGLE_PLAY_REQUEST_TIMEOUT_SECONDS = 5
_admob_keys_cache: tuple[float, dict[int, object]] | None = None
logger = logging.getLogger("quiz.monetization")
balance_logger = logging.getLogger("quiz.balance")

app.add_middleware(
    CORSMiddleware,
    allow_origins=os.getenv("QUIZ_ALLOWED_ORIGINS", "*").split(","),
    allow_credentials=False,
    allow_methods=["GET", "POST"],
    allow_headers=["Authorization", "Content-Type"],
)


def get_db():
    db = SessionLocal()
    try:
        yield db
    finally:
        db.close()


@app.on_event("startup")
def on_startup() -> None:
    if os.getenv("QUIZ_RESET_DB_ON_STARTUP", "0") == "1":
        Base.metadata.drop_all(bind=engine)
    Base.metadata.create_all(bind=engine)
    run_compatibility_migrations()
    db = SessionLocal()
    try:
        seed_database(db)
    finally:
        db.close()


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "ok"}


@app.post("/api/v1/auth/anonymous", response_model=AuthResponse)
def anonymous_auth(payload: AnonymousAuthRequest, db: Session = Depends(get_db)) -> AuthResponse:
    google_uid = f"anonymous:{payload.device_id}"
    user = db.scalar(select(User).where(User.google_uid == google_uid))
    if user is None:
        now = int(time.time() * 1000)
        user = User(
            id=f"anon_{uuid.uuid4().hex}",
            google_uid=google_uid,
            display_name="Quiz Player",
            currency_balance=3500,
            last_login_at=now,
        )
        db.add(user)
        db.flush()

        defaults = db.scalars(
            select(Asset).where(Asset.asset_code.in_(["frame_classic", "crown_none"]))
        ).all()
        for asset in defaults:
            db.add(UserAsset(user_id=user.id, asset_id=asset.id, selected=True, purchased_at=now))
        db.add(CurrencyTransaction(user_id=user.id, amount=3500, reason="anonymous_signup", created_at=now))
        db.commit()
    else:
        user.last_login_at = int(time.time() * 1000)
        db.commit()

    access_token, expires_at = issue_token(user.id)
    return AuthResponse(user_id=user.id, access_token=access_token, expires_at=expires_at)


@app.post("/api/v1/auth/play-games/link", response_model=AuthResponse)
def link_play_games_account(
    payload: PlayGamesLinkRequest,
    current_user_id: str = Depends(require_user),
    db: Session = Depends(get_db),
) -> AuthResponse:
    current_user = _require_user(db, current_user_id)
    player = _verify_play_games_server_auth_code(payload.server_auth_code)
    player_id = player.get("playerId")
    if not player_id:
        raise HTTPException(status_code=status.HTTP_502_BAD_GATEWAY, detail="Google Play player verification failed")

    google_uid = f"playgames:{player_id}"
    linked_user = db.scalar(select(User).where(User.google_uid == google_uid))
    if linked_user is not None and linked_user.id != current_user.id:
        # A fresh anonymous install is only a temporary local identity. When
        # the Play Games account already exists, switch to its canonical data
        # instead of duplicating the account or adding the starter balance.
        if current_user.google_uid and current_user.google_uid.startswith("playgames:"):
            raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail="Another Google Play account is already linked")
        user = linked_user
    else:
        user = current_user
        user.google_uid = google_uid

    # The player API may omit profile data. Do not overwrite the app name with
    # an empty value; the stable playerId is the actual account identity.
    if not user.display_name or user.display_name == "Quiz Player":
        user.display_name = player.get("displayName") or user.display_name or "Quiz Player"
    user.last_login_at = int(time.time() * 1000)
    db.commit()

    access_token, expires_at = issue_token(user.id)
    return AuthResponse(user_id=user.id, access_token=access_token, expires_at=expires_at)


def _verify_play_games_server_auth_code(server_auth_code: str) -> dict:
    client_id = os.getenv("GOOGLE_PLAY_SERVER_CLIENT_ID", "").strip()
    client_secret = os.getenv("GOOGLE_PLAY_SERVER_CLIENT_SECRET", "").strip()
    if not client_id or not client_secret:
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail="Google Play server credentials are not configured",
        )

    token_payload = urlencode({
        "code": server_auth_code,
        "client_id": client_id,
        "client_secret": client_secret,
        "grant_type": "authorization_code",
        "redirect_uri": "",
    }).encode("utf-8")
    try:
        token_request = UrlRequest(
            GOOGLE_PLAY_TOKEN_URL,
            data=token_payload,
            headers={"Content-Type": "application/x-www-form-urlencoded"},
            method="POST",
        )
        with urlopen(token_request, timeout=GOOGLE_PLAY_REQUEST_TIMEOUT_SECONDS) as response:
            token_response = json.loads(response.read().decode("utf-8"))
        access_token = token_response.get("access_token")
        if not access_token:
            raise ValueError("Google did not return an access token")

        player_request = UrlRequest(
            GOOGLE_PLAY_PLAYER_URL,
            headers={"Authorization": f"Bearer {access_token}"},
            method="GET",
        )
        with urlopen(player_request, timeout=GOOGLE_PLAY_REQUEST_TIMEOUT_SECONDS) as response:
            return json.loads(response.read().decode("utf-8"))
    except (HTTPError, URLError, TimeoutError, OSError, ValueError, json.JSONDecodeError) as error:
        logger.warning("Google Play player verification failed: %s", error)
        raise HTTPException(
            status_code=status.HTTP_502_BAD_GATEWAY,
            detail="Google Play account verification failed",
        ) from error


@app.get("/api/v1/bootstrap", response_model=BootstrapResponse)
def bootstrap(
    user_id: str = Depends(require_user),
    db: Session = Depends(get_db),
    accept_language: str | None = Header(default=None),
):
    _require_user(db, user_id)
    language = (accept_language or "en").split(",", 1)[0].strip().lower().split("-", 1)[0]
    return build_bootstrap_payload(db, user_id, language)


@app.post("/api/v1/me/currency/top-up", response_model=QuizActionResponse)
def top_up_currency(payload: TopUpCurrencyRequest, user_id: str = Depends(require_user), db: Session = Depends(get_db)):
    if payload.source not in {"test", "ad_test"} or payload.amount != 1000:
        raise HTTPException(
            status_code=status.HTTP_501_NOT_IMPLEMENTED,
            detail="Only the 1000-coin test and simulated-ad top-ups are available in the MVP",
        )
    if os.getenv("QUIZ_ENABLE_TEST_TOPUPS", "0").lower() not in {"1", "true", "yes"}:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="Test top-ups are disabled",
        )

    user = _locked_user(db, user_id)
    if payload.operation_id:
        existing = db.scalar(select(CurrencyTransaction).where(
            CurrencyTransaction.user_id == user_id,
            CurrencyTransaction.client_operation_id == payload.operation_id,
        ))
        if existing is not None:
            if existing.amount != payload.amount or existing.reason != f"currency_top_up:{payload.source}":
                raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail="Top-up operation does not match the original request")
            balance_logger.info(
                "top-up replay user=%s source=%s operation=%s balance=%s",
                user_id, payload.source, payload.operation_id, user.currency_balance,
            )
            return QuizActionResponse(balance=user.currency_balance)

    now = int(time.time() * 1000)
    balance_before = user.currency_balance
    user.currency_balance += payload.amount
    db.add(CurrencyTransaction(
        user_id=user_id,
        amount=payload.amount,
        reason=f"currency_top_up:{payload.source}",
        client_operation_id=payload.operation_id,
        created_at=now,
    ))
    db.commit()
    balance_logger.info(
        "top-up applied user=%s source=%s operation=%s before=%s after=%s",
        user_id, payload.source, payload.operation_id, balance_before, user.currency_balance,
    )
    return QuizActionResponse(balance=user.currency_balance)


@app.get("/api/v1/ads/rewarded/ssv")
def rewarded_ad_ssv(request: Request, db: Session = Depends(get_db)) -> dict[str, str]:
    """Accept only a cryptographically verified AdMob rewarded callback."""
    try:
        params = _verify_admob_ssv(str(request.url))
        transaction_id = _required_ssv_param(params, "transaction_id")
        custom_data = _required_ssv_param(params, "custom_data")
        user_id = custom_data.split("|", 1)[0]
        reward_amount = int(_required_ssv_param(params, "reward_amount"))
        reward_item = _required_ssv_param(params, "reward_item")
    except (ValueError, KeyError, TypeError) as exc:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail=f"Invalid rewarded ad callback: {exc}")

    if reward_amount != AD_REWARD_AMOUNT or reward_item != AD_REWARD_ITEM:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="Unexpected rewarded ad payload")
    configured_ad_unit = os.getenv("QUIZ_REWARDED_AD_UNIT_ID", "")
    if configured_ad_unit and _required_ssv_param(params, "ad_unit") != configured_ad_unit:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="Unexpected ad unit")

    existing = db.scalar(select(AdRewardTransaction).where(
        AdRewardTransaction.transaction_id == transaction_id
    ))
    if existing is not None:
        return {"status": "already_processed"}

    user = _locked_user(db, user_id)
    now = int(time.time() * 1000)
    user.currency_balance += reward_amount
    db.add(AdRewardTransaction(
        transaction_id=transaction_id,
        user_id=user_id,
        amount=reward_amount,
        created_at=now,
    ))
    db.add(CurrencyTransaction(
        user_id=user_id,
        amount=reward_amount,
        reason="currency_top_up:ad_ssv",
        client_operation_id=f"ad_ssv:{transaction_id}",
        created_at=now,
    ))
    db.commit()
    return {"status": "ok"}


@app.post("/api/v1/me/store/google-play/verify", response_model=QuizActionResponse)
def verify_google_play_purchase(
    payload: GooglePlayPurchaseRequest,
    user_id: str = Depends(require_user),
    db: Session = Depends(get_db),
) -> QuizActionResponse:
    """Verify, grant, and consume a Google Play consumable purchase."""
    amount_per_item = GOOGLE_PLAY_PRODUCTS.get(payload.product_id)
    if amount_per_item is None:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="Unknown Google Play product")

    credentials_file = os.getenv("GOOGLE_PLAY_SERVICE_ACCOUNT_FILE", "")
    if not credentials_file:
        raise HTTPException(status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
                            detail="Google Play verification is not configured on the server")

    try:
        from google.oauth2 import service_account
        from googleapiclient.discovery import build

        credentials = service_account.Credentials.from_service_account_file(
            credentials_file,
            scopes=["https://www.googleapis.com/auth/androidpublisher"],
        )
        publisher = build("androidpublisher", "v3", credentials=credentials, cache_discovery=False)
        package_name = os.getenv("GOOGLE_PLAY_PACKAGE_NAME", "com.maxim.quiz")
        google_purchase = publisher.purchases().products().get(
            packageName=package_name,
            productId=payload.product_id,
            token=payload.purchase_token,
        ).execute()
    except Exception as exc:
        logger.exception("Google Play verification failed")
        raise HTTPException(status_code=status.HTTP_502_BAD_GATEWAY,
                            detail=f"Google Play verification failed: {exc}")

    if int(google_purchase.get("purchaseState", 1)) != 0:
        raise HTTPException(status_code=status.HTTP_409_CONFLICT,
                            detail="Google Play purchase is pending or canceled")
    if int(google_purchase.get("consumptionState", 0)) == 1:
        raise HTTPException(status_code=status.HTTP_409_CONFLICT,
                            detail="Google Play purchase was already consumed")

    quantity = max(1, min(int(google_purchase.get("quantity", 1)), 10))
    amount = amount_per_item * quantity
    existing = db.scalar(select(GooglePlayPurchase).where(
        GooglePlayPurchase.purchase_token == payload.purchase_token
    ).with_for_update())
    if existing is not None:
        if existing.user_id != user_id or existing.product_id != payload.product_id:
            raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail="Purchase token belongs to another user")
        if existing.consumed_at is None:
            _consume_google_purchase(publisher, package_name, payload.product_id, payload.purchase_token)
            existing.consumed_at = int(time.time() * 1000)
            db.commit()
        user = _locked_user(db, user_id)
        return QuizActionResponse(balance=user.currency_balance)

    user = _locked_user(db, user_id)
    now = int(time.time() * 1000)
    user.currency_balance += amount
    purchase = GooglePlayPurchase(
        purchase_token=payload.purchase_token,
        product_id=payload.product_id,
        user_id=user_id,
        amount=amount,
        order_id=google_purchase.get("orderId"),
        created_at=now,
    )
    db.add(purchase)
    db.add(CurrencyTransaction(
        user_id=user_id,
        amount=amount,
        reason=f"google_play_purchase:{payload.product_id}",
        client_operation_id=f"google_play:{payload.purchase_token}",
        created_at=now,
    ))
    db.commit()

    # Consume only after the entitlement is durable. A retry of the same
    # token will finish consumption without crediting the user twice.
    try:
        _consume_google_purchase(publisher, package_name, payload.product_id, payload.purchase_token)
        purchase.consumed_at = int(time.time() * 1000)
        db.commit()
    except Exception:
        logger.exception("Google Play consume failed; token remains retryable")

    return QuizActionResponse(balance=user.currency_balance)


@app.get("/api/v1/me/balance", response_model=QuizActionResponse)
def get_balance(user_id: str = Depends(require_user), db: Session = Depends(get_db)):
    user = db.scalar(select(User).where(User.id == user_id))
    if user is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="User not found")
    return QuizActionResponse(balance=user.currency_balance)


@app.post("/api/v1/me/reset", response_model=QuizActionResponse)
def reset_game_data(user_id: str = Depends(require_user), db: Session = Depends(get_db)) -> QuizActionResponse:
    """Reset gameplay progress while keeping the Play Games identity linked."""
    user = _locked_user(db, user_id)
    now = int(time.time() * 1000)
    defaults = db.scalars(
        select(Asset).where(Asset.asset_code.in_(["frame_classic", "crown_none"]))
    ).all()

    db.query(UserAsset).filter(UserAsset.user_id == user_id).delete(synchronize_session=False)
    db.query(CurrencyTransaction).filter(CurrencyTransaction.user_id == user_id).delete(synchronize_session=False)
    db.query(QuizSession).filter(QuizSession.user_id == user_id).delete(synchronize_session=False)
    db.query(AdRewardTransaction).filter(AdRewardTransaction.user_id == user_id).delete(synchronize_session=False)
    for asset in defaults:
        db.add(UserAsset(user_id=user_id, asset_id=asset.id, selected=True, purchased_at=now))

    user.currency_balance = 3500
    db.add(CurrencyTransaction(
        user_id=user_id,
        amount=3500,
        reason="game_data_reset",
        created_at=now,
    ))
    db.commit()
    return QuizActionResponse(balance=user.currency_balance)


@app.post("/api/v1/me/quiz-sessions/start", response_model=QuizActionResponse)
def start_quiz(payload: StartQuizRequest, user_id: str = Depends(require_user), db: Session = Depends(get_db)):
    if payload.mode not in {"solo", "computer"}:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="Unsupported game mode")
    if payload.difficulty not in {"basic", "common", "advanced"}:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="Unsupported difficulty")

    if payload.client_session_id:
        existing = db.scalar(select(QuizSession).where(
            QuizSession.user_id == user_id,
            QuizSession.client_session_id == payload.client_session_id,
        ).with_for_update())
        if existing is not None:
            user = _locked_user(db, user_id)
            return QuizActionResponse(session_id=existing.id, balance=user.currency_balance, stake=existing.stake)

    user = _locked_user(db, user_id)
    topic = db.scalar(select(Topic).where(Topic.id == payload.topic_id, Topic.is_active.is_(True)))
    if topic is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Topic not found")
    if user.currency_balance < payload.stake:
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail="Not enough coins")

    user.currency_balance -= payload.stake
    session = QuizSession(
        user_id=user_id,
        topic_id=payload.topic_id,
        mode=payload.mode,
        difficulty=payload.difficulty,
        total_questions=payload.total_questions,
        correct_answers=0,
        stake=payload.stake,
        reward_amount=0,
        client_session_id=payload.client_session_id,
        started_at=int(time.time() * 1000),
    )
    db.add(session)
    db.flush()
    db.add(CurrencyTransaction(user_id=user_id, amount=-payload.stake,
                               reason=f"quiz_stake:{session.id}", created_at=int(time.time() * 1000)))
    db.commit()
    return QuizActionResponse(session_id=session.id, balance=user.currency_balance, stake=payload.stake)


@app.post("/api/v1/me/quiz-sessions/offline-sync", response_model=QuizActionResponse)
def sync_offline_quiz(payload: OfflineQuizSyncRequest, user_id: str = Depends(require_user), db: Session = Depends(get_db)):
    """Apply one locally completed game exactly once after an offline period."""
    if payload.mode not in {"solo", "computer"}:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="Unsupported game mode")
    if payload.difficulty not in {"basic", "common", "advanced"}:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="Unsupported difficulty")
    if payload.correct_answers > payload.total_questions:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="Invalid score")

    existing = db.scalar(select(QuizSession).where(
        QuizSession.user_id == user_id,
        QuizSession.client_session_id == payload.client_session_id,
    ).with_for_update())
    if existing is not None:
        if (existing.topic_id != payload.topic_id
                or existing.mode != payload.mode
                or existing.difficulty != payload.difficulty
                or existing.total_questions != payload.total_questions
                or existing.stake != payload.stake):
            raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail="Offline session does not match the original request")
        user = _locked_user(db, user_id)
        balance_before = user.currency_balance
        if existing.finished_at is not None:
            balance_logger.info(
                "offline-sync replay user=%s client_session=%s balance=%s",
                user_id, payload.client_session_id, user.currency_balance,
            )
            return QuizActionResponse(session_id=existing.id, balance=user.currency_balance,
                                      stake=existing.stake, reward_amount=existing.reward_amount,
                                      finished_at=existing.finished_at)
        now = int(time.time() * 1000)
        reward = 0
        if not payload.cancelled:
            percent = round(payload.correct_answers * 100 / payload.total_questions)
            multiplier = {"basic": 1.5, "common": 2.0, "advanced": 3.0}[payload.difficulty]
            reward = round(existing.stake * multiplier * percent / 100)
        existing.correct_answers = 0 if payload.cancelled else payload.correct_answers
        existing.reward_amount = reward
        existing.finished_at = now
        if reward > 0:
            user.currency_balance += reward
            db.add(CurrencyTransaction(user_id=user_id, amount=reward,
                                       reason=f"offline_quiz_reward:{existing.id}", created_at=now))
        db.commit()
        balance_logger.info(
            "offline-sync finish existing user=%s client_session=%s before=%s after=%s stake=%s reward=%s cancelled=%s",
            user_id, payload.client_session_id, balance_before, user.currency_balance,
            existing.stake, reward, payload.cancelled,
        )
        return QuizActionResponse(session_id=existing.id, balance=user.currency_balance,
                                  stake=existing.stake, reward_amount=reward,
                                  finished_at=now)

    topic = db.scalar(select(Topic).where(Topic.id == payload.topic_id, Topic.is_active.is_(True)))
    if topic is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Topic not found")

    user = _locked_user(db, user_id)
    if user.currency_balance < payload.stake:
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail="Not enough coins to synchronize offline game")

    now = int(time.time() * 1000)
    reward = 0
    if not payload.cancelled:
        percent = round(payload.correct_answers * 100 / payload.total_questions)
        multiplier = {"basic": 1.5, "common": 2.0, "advanced": 3.0}[payload.difficulty]
        reward = round(payload.stake * multiplier * percent / 100)

    balance_before = user.currency_balance
    user.currency_balance -= payload.stake
    user.currency_balance += reward
    session = QuizSession(
        user_id=user_id,
        topic_id=payload.topic_id,
        mode=payload.mode,
        difficulty=payload.difficulty,
        total_questions=payload.total_questions,
        correct_answers=0 if payload.cancelled else payload.correct_answers,
        stake=payload.stake,
        reward_amount=reward,
        client_session_id=payload.client_session_id,
        started_at=now,
        finished_at=now,
    )
    db.add(session)
    db.flush()
    db.add(CurrencyTransaction(user_id=user_id, amount=-payload.stake,
                               reason=f"offline_quiz_stake:{session.id}", created_at=now))
    if reward > 0:
        db.add(CurrencyTransaction(user_id=user_id, amount=reward,
                                   reason=f"offline_quiz_reward:{session.id}", created_at=now))
    db.commit()
    balance_logger.info(
        "offline-sync new user=%s client_session=%s before=%s after=%s stake=%s reward=%s cancelled=%s",
        user_id, payload.client_session_id, balance_before, user.currency_balance,
        payload.stake, reward, payload.cancelled,
    )
    return QuizActionResponse(session_id=session.id, balance=user.currency_balance,
                              stake=payload.stake, reward_amount=reward, finished_at=now)


@app.post("/api/v1/me/quiz-sessions/{session_id}/finish", response_model=QuizActionResponse)
def finish_quiz(session_id: int, payload: FinishQuizRequest, user_id: str = Depends(require_user), db: Session = Depends(get_db)):
    quiz_session = db.scalar(
        select(QuizSession).where(QuizSession.id == session_id, QuizSession.user_id == user_id).with_for_update()
    )
    if quiz_session is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Quiz session not found")
    if payload.total_questions != quiz_session.total_questions:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="Question count does not match session")
    if payload.correct_answers > payload.total_questions:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="Invalid score")

    user = _locked_user(db, user_id)
    if quiz_session.finished_at is not None:
        return QuizActionResponse(session_id=quiz_session.id, balance=user.currency_balance,
                                  stake=quiz_session.stake, reward_amount=quiz_session.reward_amount,
                                  finished_at=quiz_session.finished_at)

    percent = round(payload.correct_answers * 100 / payload.total_questions)
    multiplier = {"basic": 1.5, "common": 2.0, "advanced": 3.0}[quiz_session.difficulty]
    reward = round(quiz_session.stake * multiplier * percent / 100)
    finished_at = int(time.time() * 1000)
    quiz_session.correct_answers = payload.correct_answers
    quiz_session.reward_amount = reward
    quiz_session.finished_at = finished_at
    user.currency_balance += reward
    if reward > 0:
        db.add(CurrencyTransaction(user_id=user_id, amount=reward,
                                   reason=f"quiz_reward:{quiz_session.id}", created_at=finished_at))
    db.commit()
    return QuizActionResponse(session_id=quiz_session.id, balance=user.currency_balance,
                              stake=quiz_session.stake, reward_amount=reward, finished_at=finished_at)


@app.post("/api/v1/me/quiz-sessions/{session_id}/cancel", response_model=QuizActionResponse)
def cancel_quiz(session_id: int, user_id: str = Depends(require_user), db: Session = Depends(get_db)):
    quiz_session = db.scalar(
        select(QuizSession).where(QuizSession.id == session_id, QuizSession.user_id == user_id).with_for_update()
    )
    if quiz_session is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Quiz session not found")

    user = _locked_user(db, user_id)
    if quiz_session.finished_at is not None:
        return QuizActionResponse(session_id=quiz_session.id, balance=user.currency_balance,
                                  stake=quiz_session.stake, reward_amount=quiz_session.reward_amount,
                                  finished_at=quiz_session.finished_at)

    # Leaving an unfinished test closes it without returning the reserved stake.
    now = int(time.time() * 1000)
    quiz_session.finished_at = now
    quiz_session.reward_amount = 0
    db.commit()
    return QuizActionResponse(session_id=quiz_session.id, balance=user.currency_balance,
                              stake=quiz_session.stake, reward_amount=0,
                              finished_at=now)


@app.post("/api/v1/me/assets/{asset_id}/purchase", response_model=QuizActionResponse)
def purchase_asset(asset_id: int, payload: PurchaseAssetRequest, user_id: str = Depends(require_user), db: Session = Depends(get_db)):
    if payload.asset_id != asset_id:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="Asset ID mismatch")
    user = _locked_user(db, user_id)
    asset = db.scalar(select(Asset).where(Asset.id == asset_id, Asset.is_active.is_(True)))
    if asset is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Asset not found")

    if payload.operation_id:
        previous_transaction = db.scalar(
            select(CurrencyTransaction).where(
                CurrencyTransaction.user_id == user_id,
                CurrencyTransaction.client_operation_id == payload.operation_id,
            )
        )
        if previous_transaction is not None:
            expected_reason = f"asset_purchase:{asset_id}"
            if previous_transaction.reason != expected_reason:
                raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail="Operation ID was already used")
            return QuizActionResponse(asset_id=asset_id, balance=user.currency_balance)

    existing = db.scalar(select(UserAsset).where(UserAsset.user_id == user_id, UserAsset.asset_id == asset_id))
    if existing is not None:
        return QuizActionResponse(asset_id=asset_id, balance=user.currency_balance)
    if user.currency_balance < asset.price:
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail="Not enough coins")

    balance_before = user.currency_balance
    user.currency_balance -= asset.price
    db.query(UserAsset).filter(UserAsset.user_id == user_id).filter(
        UserAsset.asset_id.in_(select(Asset.id).where(Asset.asset_type == asset.asset_type))
    ).update({UserAsset.selected: False}, synchronize_session=False)
    db.add(UserAsset(user_id=user_id, asset_id=asset_id, selected=True, purchased_at=int(time.time() * 1000)))
    db.add(CurrencyTransaction(user_id=user_id, amount=-asset.price,
                               reason=f"asset_purchase:{asset_id}", created_at=int(time.time() * 1000),
                               client_operation_id=payload.operation_id))
    db.commit()
    balance_logger.info(
        "asset purchase user=%s asset=%s operation=%s before=%s after=%s price=%s",
        user_id, asset_id, payload.operation_id, balance_before, user.currency_balance, asset.price,
    )
    return QuizActionResponse(asset_id=asset_id, balance=user.currency_balance)


@app.post("/api/v1/me/assets/{asset_id}/select", response_model=QuizActionResponse)
def select_asset(asset_id: int, user_id: str = Depends(require_user), db: Session = Depends(get_db)):
    user = _require_user(db, user_id)
    asset = db.scalar(select(Asset).where(Asset.id == asset_id, Asset.is_active.is_(True)))
    owned = db.scalar(select(UserAsset).where(UserAsset.user_id == user_id, UserAsset.asset_id == asset_id))
    if asset is None or owned is None:
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail="Asset is not owned")
    db.query(UserAsset).filter(UserAsset.user_id == user_id).filter(
        UserAsset.asset_id.in_(select(Asset.id).where(Asset.asset_type == asset.asset_type))
    ).update({UserAsset.selected: False}, synchronize_session=False)
    owned.selected = True
    db.commit()
    return QuizActionResponse(asset_id=asset_id, balance=user.currency_balance)


def _require_user(db: Session, user_id: str) -> User:
    user = db.scalar(select(User).where(User.id == user_id))
    if user is None:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="User not found")
    return user


def _locked_user(db: Session, user_id: str) -> User:
    user = db.scalar(select(User).where(User.id == user_id).with_for_update())
    if user is None:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="User not found")
    return user


def _required_ssv_param(params: dict[str, list[str]], name: str) -> str:
    values = params.get(name)
    if not values or not values[0]:
        raise ValueError(f"missing {name}")
    return values[0]


def _verify_admob_ssv(raw_url: str) -> dict[str, list[str]]:
    """Verify the exact signed query string using AdMob's rotating ECDSA keys."""
    parts = urlsplit(raw_url)
    query = parts.query
    if "&signature=" not in query or "&key_id=" not in query:
        raise ValueError("missing signature or key_id")
    signed_query, signature_tail = query.rsplit("&signature=", 1)
    signature_value, key_tail = signature_tail.split("&key_id=", 1)
    if not signature_value or not key_tail.isdigit():
        raise ValueError("invalid signature metadata")

    signature = base64.urlsafe_b64decode(signature_value + "=" * (-len(signature_value) % 4))
    key_id = int(key_tail)
    keys = _admob_verification_keys()
    public_key = keys.get(key_id)
    if public_key is None:
        raise ValueError("unknown AdMob key")

    from cryptography.hazmat.primitives import hashes
    from cryptography.hazmat.primitives.asymmetric import ec
    public_key.verify(signature, signed_query.encode("utf-8"), ec.ECDSA(hashes.SHA256()))
    return parse_qs(query, keep_blank_values=True)


def _admob_verification_keys() -> dict[int, object]:
    global _admob_keys_cache
    now = time.time()
    if _admob_keys_cache is not None and now - _admob_keys_cache[0] < 24 * 60 * 60:
        return _admob_keys_cache[1]

    from urllib.request import urlopen
    from cryptography.hazmat.primitives.serialization import load_der_public_key

    with urlopen(AD_SSV_KEYS_URL, timeout=5) as response:
        payload = json.loads(response.read().decode("utf-8"))
    keys = {
        int(item["keyId"]): load_der_public_key(base64.b64decode(item["base64"]))
        for item in payload.get("keys", [])
    }
    if not keys:
        raise ValueError("AdMob returned no verification keys")
    _admob_keys_cache = (now, keys)
    return keys


def _consume_google_purchase(publisher, package_name: str, product_id: str, token: str) -> None:
    publisher.purchases().products().consume(
        packageName=package_name,
        productId=product_id,
        token=token,
    ).execute()

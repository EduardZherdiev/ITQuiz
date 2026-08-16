import base64
import hashlib
import hmac
import os
import time

from fastapi import Header, HTTPException, status


TOKEN_TTL_SECONDS = 60 * 60 * 24 * 30


def _secret() -> bytes:
    return os.getenv("QUIZ_AUTH_SECRET", "change-me-in-production").encode("utf-8")


def issue_token(user_id: str) -> tuple[str, int]:
    expires_at = int(time.time()) + TOKEN_TTL_SECONDS
    payload = f"{user_id}:{expires_at}".encode("utf-8")
    signature = hmac.new(_secret(), payload, hashlib.sha256).digest()
    encoded_payload = base64.urlsafe_b64encode(payload).decode("ascii").rstrip("=")
    encoded_signature = base64.urlsafe_b64encode(signature).decode("ascii").rstrip("=")
    return f"{encoded_payload}.{encoded_signature}", expires_at


def _decode_token(token: str) -> str:
    try:
        encoded_payload, encoded_signature = token.split(".", 1)
        payload = base64.urlsafe_b64decode(encoded_payload + "==")
        expected_signature = hmac.new(_secret(), payload, hashlib.sha256).digest()
        actual_signature = base64.urlsafe_b64decode(encoded_signature + "==")
        if not hmac.compare_digest(actual_signature, expected_signature):
            raise ValueError("invalid signature")
        user_id, expires_at = payload.decode("utf-8").split(":", 1)
        if int(expires_at) < int(time.time()):
            raise ValueError("expired token")
        if not user_id:
            raise ValueError("empty user")
        return user_id
    except (ValueError, TypeError, UnicodeDecodeError):
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Invalid access token")


def require_user(authorization: str | None = Header(default=None)) -> str:
    if not authorization or not authorization.startswith("Bearer "):
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Bearer token required")
    return _decode_token(authorization[7:].strip())

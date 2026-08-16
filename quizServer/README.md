# Quiz Server

FastAPI backend for the Quiz app.

## Run locally

```bash
cd quizServer
python -m venv .venv
.venv\Scripts\activate
pip install -r requirements.txt
copy .env.example .env
uvicorn app.main:app --reload --port 8000
```

Recommended local test database name: `quizdb_test`.

## API

- `GET /health`
- `POST /api/v1/auth/anonymous`
- `GET /api/v1/bootstrap` (Bearer token)
- `POST /api/v1/me/quiz-sessions/start`
- `POST /api/v1/me/quiz-sessions/{id}/finish`
- `POST /api/v1/me/assets/{id}/purchase`
- `POST /api/v1/me/assets/{id}/select`

The server runs the current MVP in solo mode. Set `QUIZ_RESET_DB_ON_STARTUP=1`
only when intentionally rebuilding a development database. Keep it disabled for
normal restarts.

If this project already has the old two-topic development database, set the flag
to `1` for one startup, then return it to `0`. This rebuilds the seed with the
15 current topics and 675 questions.

The Android emulator uses `http://10.0.2.2:8000/` by default. For another host,
build with `-PQUIZ_API_BASE_URL=http://<host>:8000/`; production must use HTTPS.

## Render deployment

The repository contains a root `render.yaml` Blueprint that creates a Render
Postgres database and a Docker Web Service for `quizServer`. In Render, choose
New → Blueprint and connect this repository. Keep the database and web service
in the same region so the service can use the database's internal connection
string.

The Blueprint keeps `QUIZ_RESET_DB_ON_STARTUP=0` and disables test top-ups. The
production service listens on Render's `PORT` and uses `/health` as its health
check. Add the AdMob and Google Play secret values in the Render Dashboard;
never commit them to this repository.

## Hybrid API tests

Install development dependencies and run the isolated offline/online scenarios:

```bash
pip install -r requirements-dev.txt
python -m pytest -q tests/test_hybrid_api.py
```

The tests create a temporary SQLite database, so they do not reset or modify
the development `quiz.db`.

## Monetization configuration

The Android project uses Google's rewarded-ad test IDs and Google Play test
product IDs by default. Do not publish with those values. For a release build,
provide your own AdMob application/rewarded-unit IDs and set
`REWARDED_SERVER_SOURCE=ad_ssv`.

The server-side rewarded callback is:

```text
https://<your-public-host>/api/v1/ads/rewarded/ssv
```

Enable SSV for the rewarded ad unit in AdMob and install the payment
dependencies with `pip install -r requirements-payments.txt`. For Play
Billing verification, place a Google Play service-account JSON file outside
the repository and set `GOOGLE_PLAY_SERVICE_ACCOUNT_FILE` and
`GOOGLE_PLAY_PACKAGE_NAME`. The server verifies the purchase token before
crediting coins and consumes the product afterward.

The local-only test top-up endpoint is controlled by
`QUIZ_ENABLE_TEST_TOPUPS`; keep it at `1` only for development and set it to
`0` before exposing the server publicly.

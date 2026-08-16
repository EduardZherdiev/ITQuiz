from sqlalchemy import inspect, text

from app.database import engine


def run_compatibility_migrations() -> None:
    """Apply the small additive migrations needed by the MVP schema.

    The project does not yet use Alembic. These migrations are deliberately
    additive and keep existing development databases usable when the server
    is upgraded.
    """
    inspector = inspect(engine)
    with engine.begin() as connection:
        if "assets" in inspector.get_table_names():
            asset_columns = {column["name"] for column in inspector.get_columns("assets")}
            if "asset_code" not in asset_columns:
                connection.execute(text("ALTER TABLE assets ADD COLUMN asset_code VARCHAR(64)"))
                connection.execute(text("UPDATE assets SET asset_code = LOWER(asset_type) WHERE asset_code IS NULL"))

        if "quiz_sessions" in inspector.get_table_names():
            session_columns = {column["name"] for column in inspector.get_columns("quiz_sessions")}
            if "stake" not in session_columns:
                connection.execute(text("ALTER TABLE quiz_sessions ADD COLUMN stake INTEGER NOT NULL DEFAULT 0"))
            if "reward_amount" not in session_columns:
                connection.execute(text("ALTER TABLE quiz_sessions ADD COLUMN reward_amount INTEGER NOT NULL DEFAULT 0"))
            if "client_session_id" not in session_columns:
                connection.execute(text("ALTER TABLE quiz_sessions ADD COLUMN client_session_id VARCHAR(128)"))
            connection.execute(text("CREATE INDEX IF NOT EXISTS ix_quiz_sessions_client_session_id ON quiz_sessions (client_session_id)"))

        if "currency_transactions" in inspector.get_table_names():
            transaction_columns = {column["name"] for column in inspector.get_columns("currency_transactions")}
            if "client_operation_id" not in transaction_columns:
                connection.execute(text("ALTER TABLE currency_transactions ADD COLUMN client_operation_id VARCHAR(128)"))
            connection.execute(text(
                "CREATE INDEX IF NOT EXISTS ix_currency_transactions_client_operation_id "
                "ON currency_transactions (client_operation_id)"
            ))

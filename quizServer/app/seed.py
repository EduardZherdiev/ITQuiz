from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timezone
from typing import Iterable

from sqlalchemy import select
from sqlalchemy.orm import Session

from app.models import (
    Asset,
    CurrencyTransaction,
    Option,
    OptionText,
    Question,
    QuestionText,
    QuizSession,
    Topic,
    TopicText,
    User,
    UserAsset,
)
from app.translations import localized_fact, localized_topic


LANGUAGE_CODE = "en"
SERVER_REVISION = 4
QUESTION_COUNTS = {"basic": 10, "common": 15, "advanced": 20}


@dataclass(frozen=True)
class QuestionTemplate:
    prompt: str
    explanation: str
    options: tuple[str, str, str, str]
    correct_index: int


TOPIC_BLUEPRINTS = [
    (1, "AI", "AI", "Artificial intelligence basics, models, and applications.", ["machine learning", "neural networks", "training data", "inference", "automation"]),
    (2, "ALL", "ALL", "General knowledge and broad foundations across topics.", ["overview", "fundamentals", "categories", "comparison", "context"]),
    (3, "ALGO", "ALGO", "Algorithmic thinking, steps, and problem solving.", ["steps", "complexity", "correctness", "sorting", "searching"]),
    (4, "ARCH", "ARCH", "Software and system architecture concepts.", ["layers", "services", "scalability", "components", "boundaries"]),
    (5, "C", "C", "C language syntax, memory, and low-level programming.", ["pointers", "arrays", "memory", "headers", "compilation"]),
    (6, "CPP", "C++", "C++ language features, OOP, and standard library basics.", ["classes", "templates", "references", "RAII", "STL"]),
    (7, "CSHARP", "C#", ".NET language syntax, types, and fundamentals.", ["properties", "LINQ", "delegates", "namespaces", "async"]),
    (8, "DB", "DB", "Database concepts, queries, and relational modeling.", ["tables", "indexes", "joins", "transactions", "normalization"]),
    (9, "JAVA", "JAVA", "Java language basics, OOP, collections, and concurrency.", ["class", "JVM", "collections", "exceptions", "threads"]),
    (10, "JS", "JS", "JavaScript syntax, browser behavior, and async patterns.", ["functions", "objects", "promises", "events", "DOM"]),
    (11, "MATH", "MATH", "Mathematical foundations used in computing.", ["logic", "sets", "probability", "graphs", "proofs"]),
    (12, "NET", "NET", "Computer networking concepts and protocols.", ["TCP", "HTTP", "routing", "packets", "DNS"]),
    (13, "OS", "OS", "Operating system concepts and process management.", ["processes", "threads", "memory", "scheduling", "files"]),
    (14, "PY", "PY", "Python syntax, data structures, and scripting basics.", ["indentation", "lists", "modules", "functions", "virtual environments"]),
    (15, "SQL", "SQL", "SQL queries, joins, filtering, and relational operations.", ["SELECT", "WHERE", "JOIN", "GROUP BY", "indexes"]),
]


def utc_now_millis() -> int:
    return int(datetime.now(tz=timezone.utc).timestamp() * 1000)


def _question_template(title: str, fact: str, difficulty: str, index: int, language: str = LANGUAGE_CODE) -> QuestionTemplate:
    if language == "ru":
        if difficulty == "basic":
            prompt = f"Какое понятие наиболее тесно связано с темой «{fact}» в разделе «{title}»?"
            explanation = f"Понятие «{fact}» относится к базовым идеям раздела «{title}»."
        elif difficulty == "common":
            prompt = f"Какое утверждение лучше всего описывает тему «{fact}» в разделе «{title}»?"
            explanation = f"Тема «{fact}» часто встречается в разделе «{title}»."
        else:
            prompt = f"Какой пример лучше всего показывает применение темы «{fact}» в разделе «{title}»?"
            explanation = f"Тема «{fact}» относится к продвинутым идеям раздела «{title}»."
        options = (fact, f"Несвязанная деталь раздела «{title}»", "Выбор настройки интерфейса", "Шаг миграции базы данных")
    elif language == "uk":
        if difficulty == "basic":
            prompt = f"Яке поняття найбільш тісно пов’язане з темою «{fact}» у розділі «{title}»?"
            explanation = f"Поняття «{fact}» належить до базових ідей розділу «{title}»."
        elif difficulty == "common":
            prompt = f"Яке твердження найкраще описує тему «{fact}» у розділі «{title}»?"
            explanation = f"Тема «{fact}» часто трапляється в розділі «{title}»."
        else:
            prompt = f"Який приклад найкраще показує застосування теми «{fact}» у розділі «{title}»?"
            explanation = f"Тема «{fact}» належить до складніших ідей розділу «{title}»."
        options = (fact, f"Непов’язана деталь розділу «{title}»", "Вибір налаштування інтерфейсу", "Крок міграції бази даних")
    elif difficulty == "basic":
        prompt = f"Which concept is most closely associated with {fact} in {title}?"
        explanation = f"{fact.title()} is one of the basic ideas in {title}."
        options = (fact, f"An unrelated {title} detail", "A UI configuration choice", "A database migration step")
    elif difficulty == "common":
        prompt = f"Which statement best describes {fact} in {title}?"
        explanation = f"{fact.title()} is a common topic in {title}."
        options = (fact, f"An unrelated {title} detail", "A UI configuration choice", "A database migration step")
    else:
        prompt = f"Which example best demonstrates {fact} in {title}?"
        explanation = f"{fact.title()} is an advanced idea in {title}."
        options = (fact, f"An unrelated {title} detail", "A UI configuration choice", "A database migration step")

    return QuestionTemplate(
        prompt=f"{prompt} ({index})",
        explanation=explanation,
        options=options,
        correct_index=0,
    )


def _repeat(values: list[str], count: int) -> Iterable[tuple[int, str]]:
    for index in range(count):
        yield index + 1, values[index % len(values)]


def seed_database(session: Session) -> None:
    if session.scalar(select(Topic.id).limit(1)) is None:
        now = utc_now_millis()
        topics = [
            Topic(id=topic_id, code=code, icon_url=None, created_at=now, updated_at=now,
                  plays_count=0, likes_count=0, views_count=0, author_user_id="system",
                  is_public=True, is_active=True)
            for topic_id, code, _, _, _ in TOPIC_BLUEPRINTS
        ]
        topic_texts = [
            TopicText(topic_id=topic_id, language_code=LANGUAGE_CODE, title=title,
                      description=description, abbr=abbr)
            for topic_id, _, abbr, description, _ in TOPIC_BLUEPRINTS
            for title in [abbr]
        ]

        questions: list[Question] = []
        question_texts: list[QuestionText] = []
        options: list[Option] = []
        option_texts: list[OptionText] = []
        question_id = 1000
        option_id = 5000

        for topic_id, _, title, _, facts in TOPIC_BLUEPRINTS:
            for difficulty in ("basic", "common", "advanced"):
                for index, fact in _repeat(facts, QUESTION_COUNTS[difficulty]):
                    template = _question_template(title, fact, difficulty, index)
                    questions.append(Question(id=question_id, topic_id=topic_id, difficulty=difficulty, is_active=True))
                    question_texts.append(QuestionText(question_id=question_id, language_code=LANGUAGE_CODE,
                                                       question_text=template.prompt, explanation=template.explanation))
                    for option_index, option_text in enumerate(template.options):
                        options.append(Option(id=option_id, question_id=question_id,
                                              is_correct=option_index == template.correct_index))
                        option_texts.append(OptionText(option_id=option_id, language_code=LANGUAGE_CODE,
                                                       option_text=option_text))
                        option_id += 1
                    question_id += 1

        assets = [
            Asset(id=1, asset_type="FRAME", asset_code="frame_classic", price=0, is_active=True),
            Asset(id=2, asset_type="FRAME", asset_code="frame_neon", price=1800, is_active=True),
            Asset(id=3, asset_type="FRAME", asset_code="frame_royal", price=4200, is_active=True),
            Asset(id=4, asset_type="CROWN", asset_code="crown_none", price=0, is_active=True),
            Asset(id=5, asset_type="CROWN", asset_code="crown_bronze", price=1800, is_active=True),
            Asset(id=6, asset_type="CROWN", asset_code="crown_silver", price=2500, is_active=True),
            Asset(id=7, asset_type="CROWN", asset_code="crown_gold", price=4200, is_active=True),
            Asset(id=8, asset_type="CROWN", asset_code="crown_brilliant", price=6500, is_active=True),
        ]
        user = User(id="user_test", google_uid="seed-user", email="user@example.com",
                    display_name="Guest", photo_url=None, currency_balance=3500, last_login_at=now)
        user_assets = [
            UserAsset(user_id=user.id, asset_id=1, selected=True, purchased_at=now),
            UserAsset(user_id=user.id, asset_id=4, selected=True, purchased_at=now),
        ]
        transactions = [CurrencyTransaction(user_id=user.id, amount=3500, reason="seed_balance", created_at=now)]
        sessions = [QuizSession(user_id=user.id, topic_id=9, mode="solo", difficulty="basic",
                                total_questions=10, correct_answers=8, stake=1000, reward_amount=1500,
                                client_session_id=None,
                                started_at=now - 600000, finished_at=now - 300000)]

        # PostgreSQL enforces foreign keys during the flush.  The models do
        # not declare ORM relationships, so SQLAlchemy cannot infer the
        # insert order from the ForeignKey columns alone.  Insert each level
        # explicitly to keep the seed portable between SQLite and PostgreSQL.
        session.add_all(topics + topic_texts + assets)
        session.flush()

        session.add_all(questions)
        session.flush()

        session.add_all(question_texts)
        session.flush()

        session.add_all(options)
        session.flush()

        session.add_all(option_texts)
        session.add(user)
        session.flush()
        session.add_all(user_assets + transactions + sessions)
        session.commit()

    ensure_localized_texts(session)
    legacy_guest_users = session.scalars(
        select(User).where(User.display_name.in_(["Quiz Player", "Max Tester"]))
    ).all()
    for legacy_user in legacy_guest_users:
        legacy_user.display_name = "Guest"
    if legacy_guest_users:
        session.commit()


def ensure_localized_texts(session: Session) -> None:
    for language in ("en", "ru", "uk"):
        for topic_id, code, fallback_title, fallback_description, facts in TOPIC_BLUEPRINTS:
            title, abbr, description = localized_topic(code, language, fallback_title, fallback_title, fallback_description)
            topic_text = session.scalar(select(TopicText).where(
                TopicText.topic_id == topic_id,
                TopicText.language_code == language,
            ))
            if topic_text is None:
                session.add(TopicText(topic_id=topic_id, language_code=language, title=title,
                                      description=description, abbr=abbr))
            else:
                # Keep existing development databases in sync with the current
                # full names and concise one-line descriptions.
                topic_text.title = title
                topic_text.description = description
                topic_text.abbr = abbr

            questions = session.scalars(
                select(Question).where(Question.topic_id == topic_id).order_by(Question.id)
            ).all()
            by_difficulty = {difficulty: 0 for difficulty in ("basic", "common", "advanced")}
            for question in questions:
                if question.difficulty not in by_difficulty:
                    continue
                by_difficulty[question.difficulty] += 1
                index = by_difficulty[question.difficulty]
                fact = localized_fact(facts[(index - 1) % len(facts)], language)
                template = _question_template(title, fact, question.difficulty, index, language)
                if session.scalar(select(QuestionText.id).where(
                        QuestionText.question_id == question.id,
                        QuestionText.language_code == language)) is None:
                    session.add(QuestionText(question_id=question.id, language_code=language,
                                             question_text=template.prompt, explanation=template.explanation))
                question_options = session.scalars(select(Option).where(Option.question_id == question.id).order_by(Option.id)).all()
                for option, option_text in zip(question_options, template.options):
                    if session.scalar(select(OptionText.id).where(
                            OptionText.option_id == option.id,
                            OptionText.language_code == language)) is None:
                        session.add(OptionText(option_id=option.id, language_code=language,
                                               option_text=option_text))
    session.commit()


def build_bootstrap_payload(session: Session, user_id: str, language: str = LANGUAGE_CODE) -> dict:
    language = language if language in {"en", "ru", "uk"} else LANGUAGE_CODE
    return {
        "server_revision": SERVER_REVISION,
        "topics": session.query(Topic).filter(Topic.is_active.is_(True)).order_by(Topic.id).all(),
        "topic_texts": session.query(TopicText).filter(TopicText.language_code == language).order_by(TopicText.topic_id).all(),
        "questions": session.query(Question).filter(Question.is_active.is_(True)).order_by(Question.id).all(),
        "question_texts": session.query(QuestionText).filter(QuestionText.language_code == language).order_by(QuestionText.question_id).all(),
        "options": session.query(Option).order_by(Option.id).all(),
        "option_texts": session.query(OptionText).filter(OptionText.language_code == language).order_by(OptionText.option_id).all(),
        "assets": session.query(Asset).filter(Asset.is_active.is_(True)).order_by(Asset.id).all(),
        "user_assets": session.query(UserAsset).filter(UserAsset.user_id == user_id).order_by(UserAsset.asset_id).all(),
        "users": session.query(User).filter(User.id == user_id).all(),
        "currency_transactions": session.query(CurrencyTransaction).filter(CurrencyTransaction.user_id == user_id).order_by(CurrencyTransaction.id).all(),
        "quiz_sessions": session.query(QuizSession).filter(QuizSession.user_id == user_id).order_by(QuizSession.id).all(),
    }

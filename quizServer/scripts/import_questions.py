"""Import quiz content from a validated JSON file.

Run from the quizServer directory:

    python -m scripts.import_questions content/questions.example.json --dry-run
    python -m scripts.import_questions content/algo_questions.json content/algo_questions_common.json content/algo_questions_advanced.json --replace-all-questions

The target database is selected through DATABASE_URL. The importer is
deliberately a local/admin command instead of a public HTTP endpoint.
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Any

from sqlalchemy import delete, select

from app.database import SessionLocal
from app.models import Option, OptionText, Question, QuestionText, Topic, TopicText


SUPPORTED_LANGUAGES = {"en", "ru", "uk"}
SUPPORTED_DIFFICULTIES = {"basic", "common", "advanced"}


class ContentError(ValueError):
    pass


def require_mapping(value: Any, label: str) -> dict[str, Any]:
    if not isinstance(value, dict):
        raise ContentError(f"{label} must be an object")
    return value


def require_string(value: Any, label: str) -> str:
    if not isinstance(value, str) or not value.strip():
        raise ContentError(f"{label} must be a non-empty string")
    return value.strip()


def translations(value: Any, label: str) -> dict[str, Any]:
    value = require_mapping(value, label)
    unknown = set(value) - SUPPORTED_LANGUAGES
    if unknown:
        raise ContentError(f"{label} contains unsupported languages: {sorted(unknown)}")
    if "en" not in value:
        raise ContentError(f"{label} must contain an English (en) translation")
    return value


def upsert_topic_texts(session, topic: Topic, data: dict[str, Any], topic_label: str) -> None:
    for language, raw_text in translations(data, f"{topic_label}.translations").items():
        text = require_mapping(raw_text, f"{topic_label}.translations.{language}")
        title = require_string(text.get("title"), f"{topic_label}.translations.{language}.title")
        abbr = require_string(text.get("abbr", topic.code), f"{topic_label}.translations.{language}.abbr")
        description = text.get("description", "")
        if description is not None and not isinstance(description, str):
            raise ContentError(f"{topic_label}.translations.{language}.description must be a string")

        row = session.scalar(select(TopicText).where(
            TopicText.topic_id == topic.id,
            TopicText.language_code == language,
        ))
        if row is None:
            session.add(TopicText(
                topic_id=topic.id,
                language_code=language,
                title=title,
                description=description or "",
                abbr=abbr,
            ))
        else:
            row.title = title
            row.description = description or ""
            row.abbr = abbr


def upsert_question_texts(session, question: Question, data: dict[str, Any], question_label: str) -> None:
    for language, raw_text in translations(data, f"{question_label}.translations").items():
        text = require_mapping(raw_text, f"{question_label}.translations.{language}")
        question_text = require_string(
            text.get("question"),
            f"{question_label}.translations.{language}.question",
        )
        explanation = text.get("explanation")
        if explanation is not None and not isinstance(explanation, str):
            raise ContentError(f"{question_label}.translations.{language}.explanation must be a string")

        row = session.scalar(select(QuestionText).where(
            QuestionText.question_id == question.id,
            QuestionText.language_code == language,
        ))
        if row is None:
            session.add(QuestionText(
                question_id=question.id,
                language_code=language,
                question_text=question_text,
                explanation=explanation,
            ))
        else:
            row.question_text = question_text
            row.explanation = explanation


def option_texts(value: Any, label: str) -> dict[str, str]:
    value = translations(value, label)
    result: dict[str, str] = {}
    for language, text in value.items():
        result[language] = require_string(text, f"{label}.{language}")
    return result


def upsert_option_texts(session, option: Option, data: dict[str, Any], option_label: str) -> None:
    for language, text in option_texts(data, f"{option_label}.translations").items():
        row = session.scalar(select(OptionText).where(
            OptionText.option_id == option.id,
            OptionText.language_code == language,
        ))
        if row is None:
            session.add(OptionText(
                option_id=option.id,
                language_code=language,
                option_text=text,
            ))
        else:
            row.option_text = text


def get_or_create_topic(session, data: dict[str, Any], index: int) -> Topic:
    label = f"topics[{index}]"
    code = require_string(data.get("code"), f"{label}.code").upper()
    topic_id = data.get("id")
    if topic_id is not None and (not isinstance(topic_id, int) or topic_id <= 0):
        raise ContentError(f"{label}.id must be a positive integer")

    topic = session.scalar(select(Topic).where(Topic.code == code))
    if topic is None and topic_id is not None:
        topic = session.get(Topic, topic_id)
        if topic is not None and topic.code != code:
            raise ContentError(f"{label}.id belongs to topic {topic.code}, not {code}")
    topic_was_created = topic is None
    if topic_was_created:
        if "translations" not in data:
            raise ContentError(
                f"{label} is a new topic and must contain translations; "
                "existing topics only need code and questions"
            )
        topic = Topic(
            id=topic_id,
            code=code,
            icon_url=data.get("icon_url"),
            created_at=0,
            updated_at=0,
            plays_count=0,
            likes_count=0,
            views_count=0,
            author_user_id="content-import",
            is_public=True,
            is_active=True,
        )
        session.add(topic)
        session.flush()
    else:
        if "icon_url" in data:
            topic.icon_url = data["icon_url"]
        topic.is_active = data.get("is_active", True)

    if "translations" in data:
        upsert_topic_texts(session, topic, data.get("translations"), label)
    return topic


def get_or_create_question(
    session,
    topic: Topic,
    data: dict[str, Any],
    index: int,
) -> Question:
    label = f"topic {topic.code}.questions[{index}]"
    difficulty = require_string(data.get("difficulty"), f"{label}.difficulty").lower()
    if difficulty not in SUPPORTED_DIFFICULTIES:
        raise ContentError(f"{label}.difficulty must be one of {sorted(SUPPORTED_DIFFICULTIES)}")

    question_id = data.get("id")
    if question_id is not None and (not isinstance(question_id, int) or question_id <= 0):
        raise ContentError(f"{label}.id must be a positive integer")

    question = session.get(Question, question_id) if question_id is not None else None
    if question is not None and question.topic_id != topic.id:
        raise ContentError(f"{label}.id belongs to another topic")
    if question is None:
        question = Question(
            id=question_id,
            topic_id=topic.id,
            difficulty=difficulty,
            is_active=data.get("is_active", True),
        )
        session.add(question)
        session.flush()
    else:
        question.difficulty = difficulty
        question.is_active = data.get("is_active", True)

    upsert_question_texts(session, question, data.get("translations"), label)
    return question


def import_content(session, payload: dict[str, Any]) -> tuple[int, int, int]:
    raw_topics = payload.get("topics")
    if not isinstance(raw_topics, list) or not raw_topics:
        raise ContentError("top-level topics must be a non-empty array")

    topic_count = 0
    question_count = 0
    option_count = 0
    for topic_index, raw_topic in enumerate(raw_topics):
        topic_data = require_mapping(raw_topic, f"topics[{topic_index}]")
        topic = get_or_create_topic(session, topic_data, topic_index)
        topic_count += 1

        raw_questions = topic_data.get("questions", [])
        if not isinstance(raw_questions, list):
            raise ContentError(f"topics[{topic_index}].questions must be an array")
        for question_index, raw_question in enumerate(raw_questions):
            question_data = require_mapping(
                raw_question,
                f"topic {topic.code}.questions[{question_index}]",
            )
            question = get_or_create_question(session, topic, question_data, question_index)
            question_count += 1

            raw_options = question_data.get("options")
            if not isinstance(raw_options, list) or len(raw_options) != 4:
                raise ContentError(
                    f"topic {topic.code}.questions[{question_index}].options must contain exactly 4 items"
                )
            correct_count = sum(1 for option in raw_options if isinstance(option, dict) and option.get("correct") is True)
            if correct_count != 1:
                raise ContentError(
                    f"topic {topic.code}.questions[{question_index}] must contain exactly one correct option"
                )

            existing_options = session.scalars(
                select(Option).where(Option.question_id == question.id).order_by(Option.id)
            ).all()
            used_option_ids: set[int] = set()
            for option_index, raw_option in enumerate(raw_options):
                option_data = require_mapping(
                    raw_option,
                    f"topic {topic.code}.questions[{question_index}].options[{option_index}]",
                )
                option_id = option_data.get("id")
                if option_id is not None and (not isinstance(option_id, int) or option_id <= 0):
                    raise ContentError("option id must be a positive integer")

                option = session.get(Option, option_id) if option_id is not None else None
                if option is not None and option.question_id != question.id:
                    raise ContentError("option id belongs to another question")
                if option is None and option_id is None and option_index < len(existing_options):
                    option = existing_options[option_index]
                if option is None:
                    option = Option(
                        id=option_id,
                        question_id=question.id,
                        is_correct=option_data["correct"],
                    )
                    session.add(option)
                    session.flush()
                else:
                    option.is_correct = option_data["correct"]
                used_option_ids.add(option.id)
                upsert_option_texts(
                    session,
                    option,
                    option_data.get("translations"),
                    f"topic {topic.code}.questions[{question_index}].options[{option_index}]",
                )
                option_count += 1

            stale_options = [option for option in existing_options if option.id not in used_option_ids]
            if stale_options:
                stale_ids = [option.id for option in stale_options]
                session.execute(delete(OptionText).where(OptionText.option_id.in_(stale_ids)))
                for option in stale_options:
                    session.delete(option)

    return topic_count, question_count, option_count


def delete_all_questions(session) -> None:
    """Remove the previous question catalog while keeping users and assets."""
    session.execute(delete(OptionText))
    session.execute(delete(Option))
    session.execute(delete(QuestionText))
    session.execute(delete(Question))


def main() -> int:
    parser = argparse.ArgumentParser(description="Import quiz questions and translations into the configured database")
    parser.add_argument("files", type=Path, nargs="+", help="one or more JSON content files")
    parser.add_argument("--dry-run", action="store_true", help="validate and process without committing changes")
    parser.add_argument(
        "--replace-all-questions",
        action="store_true",
        help="delete the current question catalog before importing all supplied files",
    )
    args = parser.parse_args()

    try:
        payloads = []
        for content_file in args.files:
            payload = json.loads(content_file.read_text(encoding="utf-8"))
            payloads.append(require_mapping(payload, f"root ({content_file})"))
    except FileNotFoundError:
        print(f"File not found: {content_file}", file=sys.stderr)
        return 2
    except json.JSONDecodeError as exc:
        print(f"Invalid JSON at line {exc.lineno}, column {exc.colno}: {exc.msg}", file=sys.stderr)
        return 2
    except ContentError as exc:
        print(f"Invalid content: {exc}", file=sys.stderr)
        return 2

    with SessionLocal() as session:
        try:
            if args.replace_all_questions:
                delete_all_questions(session)

            totals = [0, 0, 0]
            for payload in payloads:
                counts = import_content(session, payload)
                totals = [left + right for left, right in zip(totals, counts)]
            if args.dry_run:
                session.rollback()
                action = "validated (no changes committed)"
            else:
                session.commit()
                action = "imported"
        except Exception as exc:
            session.rollback()
            if isinstance(exc, ContentError):
                print(f"Invalid content: {exc}", file=sys.stderr)
                return 2
            raise

    print(f"{action}: topics={totals[0]}, questions={totals[1]}, options={totals[2]}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

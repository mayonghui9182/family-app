"""微信公众号持续会话严格契约与 JSON guard 测试。"""

import json

import pytest
from pydantic import ValidationError

from app.harness.json_guard import AIJsonGuardError, parse_conversation_turn
from app.models.conversation_schemas import (
    AskClarificationAction,
    CategoryReference,
    ConversationTurnRequest,
    ExperienceContext,
    FamilyContext,
    MemberReference,
    PresentCandidateAction,
    RefuseOrHelpAction,
)


def make_request() -> ConversationTurnRequest:
    return ConversationTurnRequest(
        conversation_id=201,
        route_revision=3,
        current_revision=2,
        message_id=101,
        user_text="周五让妈妈买菜",
        family=FamilyContext(
            family_id=11,
            family_name="一一家",
            actor_user_id=81,
            actor_role="ADULT",
        ),
        allowed_slot_keys=["assignee", "due_date", "topic"],
        experiences=[
            ExperienceContext(
                experience_id=30,
                version=2,
                normalized_key="assignee:shopping",
                value_summary="买菜通常由妈妈负责",
            )
        ],
        categories=[CategoryReference(category_id=10, name="家务")],
        members=[
            MemberReference(
                profile_id=20,
                display_name="妈妈",
                user_id=82,
            )
        ],
    )


def valid_candidate() -> dict[str, object]:
    return {
        "action": "PRESENT_CANDIDATE",
        "reply": "我整理了 1 条任务，请确认。",
        "tasks": [
            {
                "title": "周五买菜",
                "description": "购买本周食材",
                "due_date": "2026-10-09",
                "priority": "MEDIUM",
                "category_id": 10,
                "assignee_profile_id": 20,
            }
        ],
        "slot_patch": {
            "assignee": {
                "value": "妈妈",
                "source": "USER_EXPLICIT",
                "source_message_id": 101,
            }
        },
    }


def parse_payload(payload: dict[str, object]) -> object:
    return parse_conversation_turn(
        json.dumps(payload, ensure_ascii=False),
        make_request(),
    )


def test_valid_actions_return_discriminated_models() -> None:
    candidate = parse_payload(valid_candidate())
    assert isinstance(candidate, PresentCandidateAction)
    assert candidate.tasks[0].category_id == 10

    ask = parse_payload(
        {
            "action": "ASK_CLARIFICATION",
            "reply": "请问希望哪天完成？",
            "slot_patch": {},
        }
    )
    assert isinstance(ask, AskClarificationAction)

    help_action = parse_payload(
        {
            "action": "REFUSE_OR_HELP",
            "reply": "我可以帮您整理家庭任务。",
            "slot_patch": {},
        }
    )
    assert isinstance(help_action, RefuseOrHelpAction)


@pytest.mark.parametrize(
    "payload",
    [
        {"action": "CREATE_TASKS", "reply": "直接创建", "slot_patch": {}},
        {
            "action": "REFUSE_OR_HELP",
            "reply": "帮助",
            "slot_patch": {},
            "tool_call": {"name": "create_task"},
        },
        {
            "action": "PRESENT_CANDIDATE",
            "reply": "请确认",
            "tasks": [{"title": "买菜", "family_id": 999}],
            "slot_patch": {},
        },
    ],
)
def test_unknown_action_and_privileged_fields_are_rejected(
    payload: dict[str, object],
) -> None:
    with pytest.raises(AIJsonGuardError) as exc_info:
        parse_payload(payload)
    assert exc_info.value.code == 56002


@pytest.mark.parametrize(
    ("field", "value"),
    [
        ("category_id", 999),
        ("assignee_profile_id", 999),
    ],
)
def test_cross_family_candidate_references_are_rejected(
    field: str,
    value: int,
) -> None:
    payload = valid_candidate()
    tasks = payload["tasks"]
    assert isinstance(tasks, list)
    task = tasks[0]
    assert isinstance(task, dict)
    task[field] = value

    with pytest.raises(AIJsonGuardError):
        parse_payload(payload)


def test_candidate_count_must_be_between_one_and_twenty() -> None:
    empty = valid_candidate()
    empty["tasks"] = []
    with pytest.raises(AIJsonGuardError):
        parse_payload(empty)

    too_many = valid_candidate()
    too_many["tasks"] = [{"title": f"任务{i}"} for i in range(21)]
    with pytest.raises(AIJsonGuardError):
        parse_payload(too_many)


@pytest.mark.parametrize(
    "slot_patch",
    [
        {
            "unknown_slot": {
                "value": "值",
                "source": "USER_EXPLICIT",
                "source_message_id": 101,
            }
        },
        {
            "assignee": {
                "value": "妈妈",
                "source": "USER_EXPLICIT",
                "source_message_id": 999,
            }
        },
        {
            "assignee": {
                "value": "妈妈",
                "source": "EXPERIENCE_DEFAULT",
                "experience_id": 999,
            }
        },
        {
            "assignee": {
                "value": "妈妈",
                "source": "USER_EXPLICIT",
            }
        },
    ],
)
def test_slot_source_and_references_are_strict(
    slot_patch: dict[str, object],
) -> None:
    payload = {
        "action": "ASK_CLARIFICATION",
        "reply": "还需要补充信息。",
        "slot_patch": slot_patch,
    }
    with pytest.raises(AIJsonGuardError):
        parse_payload(payload)


def test_malformed_json_reply_length_and_task_fields_are_rejected() -> None:
    with pytest.raises(AIJsonGuardError):
        parse_conversation_turn("{broken", make_request())

    too_long = {
        "action": "REFUSE_OR_HELP",
        "reply": "帮" * 801,
        "slot_patch": {},
    }
    with pytest.raises(AIJsonGuardError):
        parse_payload(too_long)

    invalid_task = valid_candidate()
    tasks = invalid_task["tasks"]
    assert isinstance(tasks, list)
    task = tasks[0]
    assert isinstance(task, dict)
    task["priority"] = "URGENT"
    with pytest.raises(AIJsonGuardError):
        parse_payload(invalid_task)


def test_turn_input_rejects_unknown_or_sensitive_fields() -> None:
    raw = make_request().model_dump(mode="json")
    raw["openid"] = "should-not-enter-agent"

    with pytest.raises(ValidationError):
        ConversationTurnRequest.model_validate(raw)

"""backend 与 agent-service 共用的微信公众号持续会话契约测试。"""

from __future__ import annotations

import copy
import json
from pathlib import Path
from typing import Any

import pytest
from pydantic import TypeAdapter, ValidationError

from app.harness.json_guard import AIJsonGuardError, parse_conversation_summary
from app.models.conversation_schemas import (
    ConversationRollingSummary,
    ConversationSummaryRequest,
    ConversationTurnAction,
    ConversationTurnRequest,
)

_CONTRACT_PATH = (
    Path(__file__).resolve().parents[2]
    / "contracts"
    / "wechat-task-conversation"
    / "v1.json"
)
_TURN_ACTION_ADAPTER: TypeAdapter[ConversationTurnAction] = TypeAdapter(
    ConversationTurnAction
)


def _contract() -> dict[str, Any]:
    return json.loads(_CONTRACT_PATH.read_text(encoding="utf-8"))


def test_shared_contract_accepts_snake_case_turn_and_all_action_values() -> None:
    contract = _contract()
    raw_text = _CONTRACT_PATH.read_text(encoding="utf-8")

    request = ConversationTurnRequest.model_validate(contract["turn_request"])
    action = _TURN_ACTION_ADAPTER.validate_python(contract["turn_response"])
    action_schema = _TURN_ACTION_ADAPTER.json_schema()
    action_mapping = action_schema["discriminator"]["mapping"]

    assert request.conversation_id == 201
    assert request.family.family_id == 11
    assert action.action == "PRESENT_CANDIDATE"
    assert action.tasks[0].assignee_profile_id == 21
    assert set(action_mapping) == set(contract["action_values"])
    assert "conversation_id" in raw_text
    assert "conversationId" not in raw_text
    assert "routeRevision" not in raw_text
    assert "openid" not in contract["turn_request"]
    assert "internal_secret" not in contract["turn_request"]


def test_shared_contract_locks_candidate_limits_to_one_through_twenty() -> None:
    contract = _contract()
    response = contract["turn_response"]
    task = response["tasks"][0]
    assert contract["limits"]["candidate_min"] == 1
    assert contract["limits"]["candidate_max"] == 20

    too_few = copy.deepcopy(response)
    too_few["tasks"] = []
    too_many = copy.deepcopy(response)
    too_many["tasks"] = [copy.deepcopy(task) for _ in range(21)]

    with pytest.raises(ValidationError):
        _TURN_ACTION_ADAPTER.validate_python(too_few)
    with pytest.raises(ValidationError):
        _TURN_ACTION_ADAPTER.validate_python(too_many)


def test_shared_contract_summary_cursor_matches_last_input_message() -> None:
    contract = _contract()
    request = ConversationSummaryRequest.model_validate(contract["summary_request"])
    raw_response = json.dumps(contract["summary_response"], ensure_ascii=False)

    summary = parse_conversation_summary(raw_response, request)

    assert isinstance(summary, ConversationRollingSummary)
    assert summary.last_message_id == request.messages[-1].message_id == 102

    bad_response = copy.deepcopy(contract["summary_response"])
    bad_response["last_message_id"] = 101
    with pytest.raises(AIJsonGuardError):
        parse_conversation_summary(
            json.dumps(bad_response, ensure_ascii=False),
            request,
        )


def test_shared_contract_error_envelope_matches_agent_guard_error() -> None:
    envelope = _contract()["error_envelope"]
    error = AIJsonGuardError(envelope["code"], envelope["msg"])

    assert set(envelope) == {"code", "msg"}
    assert {"code": error.code, "msg": error.message} == envelope
    assert envelope["code"] == 56002

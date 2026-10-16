import pytest
from pydantic import ValidationError

from app.settings import Settings


def test_internal_api_secret_is_required(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.delenv("INTERNAL_API_SECRET", raising=False)

    with pytest.raises(ValidationError, match="internal_api_secret"):
        Settings(_env_file=None)


def test_internal_api_secret_rejects_blank() -> None:
    with pytest.raises(ValidationError, match="INTERNAL_API_SECRET 不能为空"):
        Settings(internal_api_secret="   ", _env_file=None)


def test_conversation_deadline_cannot_exceed_backend_read_budget() -> None:
    with pytest.raises(ValidationError, match="conversation_timeout_seconds"):
        Settings(internal_api_secret="test-secret", conversation_timeout_seconds=60, _env_file=None)

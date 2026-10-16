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


def test_old_and_new_wechat_modes_are_mutually_exclusive() -> None:
    with pytest.raises(
        ValidationError,
        match="WECHAT_INBOUND_ENABLED 与 WECHAT_CONVERSATION_ENABLED 不能同时开启",
    ):
        Settings(
            internal_api_secret="test-secret",
            wechat_inbound_enabled=True,
            wechat_conversation_enabled=True,
            _env_file=None,
        )


@pytest.mark.parametrize(
    ("legacy_enabled", "conversation_enabled"),
    [(False, False), (True, False), (False, True)],
)
def test_single_or_disabled_wechat_mode_is_allowed(
    legacy_enabled: bool,
    conversation_enabled: bool,
) -> None:
    settings = Settings(
        internal_api_secret="test-secret",
        wechat_inbound_enabled=legacy_enabled,
        wechat_conversation_enabled=conversation_enabled,
        _env_file=None,
    )

    assert settings.wechat_inbound_enabled is legacy_enabled
    assert settings.wechat_conversation_enabled is conversation_enabled

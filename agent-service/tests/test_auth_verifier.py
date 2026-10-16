"""auth_verifier：backend /api/auth/me 五分支（MockTransport 离线）。"""
from typing import Any

import httpx
import pytest

from app.models.errors import AuthError, FamilyScopeError, RoleDeniedError
from app.services.auth_verifier import AuthVerifier


def wire(payload: dict[str, Any] | int) -> AuthVerifier:
    def handler(request: httpx.Request) -> httpx.Response:
        assert request.headers["X-Access-Token"] == "tok"
        if payload == 401:
            return httpx.Response(401, json={})
        return httpx.Response(200, json=payload)

    return AuthVerifier(
        client_factory=lambda: httpx.AsyncClient(
            transport=httpx.MockTransport(handler), base_url="http://b.test", timeout=10.0
        )
    )


def me_payload(role: str, family_id: int = 1) -> dict[str, Any]:
    return {
        "code": 0,
        "data": {
            "nickname": "妈妈",
            "families": [{"id": family_id, "name": "我家", "role": role, "roleName": "x"}],
        },
    }


async def test_verify_owner_ok() -> None:
    user = await wire(me_payload("OWNER")).verify(1, "tok")
    assert (user.family_id, user.role, user.nickname) == (1, "OWNER", "妈妈")


async def test_verify_adult_ok_child_denied_40302() -> None:
    await wire(me_payload("ADULT")).verify(1, "tok")
    with pytest.raises(RoleDeniedError) as ei:
        await wire(me_payload("CHILD")).verify(1, "tok")
    assert ei.value.code == 40302 and "孩子" in ei.value.message


async def test_verify_scope_mismatch_46000() -> None:
    with pytest.raises(FamilyScopeError) as ei:
        await wire(me_payload("OWNER", family_id=2)).verify(99, "tok")
    assert ei.value.code == 46000


async def test_verify_401_and_bad_envelope_map_40103() -> None:
    with pytest.raises(AuthError) as ei:
        await wire(401).verify(1, "tok")
    assert ei.value.code == 40103
    with pytest.raises(AuthError):
        await wire({"code": 1, "data": {}}).verify(1, "tok")


async def test_verify_network_failure_map_40103() -> None:
    def boom(request: httpx.Request) -> httpx.Response:
        raise httpx.ConnectError("backend down")

    verifier = AuthVerifier(
        client_factory=lambda: httpx.AsyncClient(
            transport=httpx.MockTransport(boom), base_url="http://b.test", timeout=10.0
        )
    )
    with pytest.raises(AuthError):
        await verifier.verify(1, "tok")

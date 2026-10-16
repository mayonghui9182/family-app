"""backend 内部连接器（1 用例）：X-Internal-Secret 携带 + 403 透传。"""
import httpx
import pytest

from app.connectors.backend_client import BackendClient

from .conftest import make_settings


async def test_carries_internal_secret_and_403_passthrough() -> None:
    seen: dict[str, str] = {}

    def handler(request: httpx.Request) -> httpx.Response:
        seen["secret"] = request.headers.get("X-Internal-Secret", "")
        return httpx.Response(403, json={"code": 40300, "msg": "内部密钥缺失或不正确"})

    transport = httpx.MockTransport(handler)
    be = httpx.AsyncClient(transport=transport, base_url="http://backend.test")
    client = BackendClient(client=be, settings=make_settings())

    with pytest.raises(httpx.HTTPStatusError) as exc_info:
        await client.get_bind("o-openid-1")
    assert exc_info.value.response.status_code == 403
    assert seen["secret"] == "itest-secret"
    await be.aclose()

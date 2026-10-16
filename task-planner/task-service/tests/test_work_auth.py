"""认证边界测试：伪造身份、服务异常和内部作用域均不能越权。"""

import httpx
import pytest

from src.work_auth import AuthenticationError, verify_identity


@pytest.mark.asyncio
async def test_token_identity_ignores_forged_user_header():
    """身份只取主应用响应，不采用请求自报用户。"""
    def handle(request):
        assert request.url.path == "/api/auth/me"
        assert "x-work-user-id" not in request.headers
        return httpx.Response(200, json={"code": 0, "data": {"userId": 17}})

    identity = await verify_identity(
        {"x-access-token": "a" * 64, "x-work-user-id": "99"},
        backend_url="http://backend:8080", internal_secret="test-secret",
        transport=httpx.MockTransport(handle),
    )
    assert identity.user_id == "17"
    assert identity.headers == {"X-Access-Token": "a" * 64}


@pytest.mark.asyncio
@pytest.mark.parametrize("headers", [
    {}, {"x-work-user-id": "17"},
    {"x-internal-secret": "wrong", "x-work-user-id": "17"},
    {"x-internal-secret": "test-secret", "x-work-user-id": "default"},
    {"x-access-token": "invalid"},
])
async def test_untrusted_credentials_rejected_without_request(headers):
    """无效身份不得调用下游或获得默认用户。"""
    def handle(request):
        pytest.fail("无效身份不应访问下游")
    with pytest.raises(AuthenticationError):
        await verify_identity(headers, backend_url="http://backend:8080",
                              internal_secret="test-secret",
                              transport=httpx.MockTransport(handle))


@pytest.mark.asyncio
@pytest.mark.parametrize("status,body", [
    (401, {}), (302, {}), (200, {"code": 40102}),
    (200, {"code": 0, "data": {"userId": True}}),
    (200, {"code": 0, "data": {"userId": "17"}}),
])
async def test_expired_or_malformed_backend_response_rejected(status, body):
    with pytest.raises(AuthenticationError):
        await verify_identity({"x-access-token": "a" * 64},
                              backend_url="http://backend:8080", internal_secret="test-secret",
                              transport=httpx.MockTransport(lambda r: httpx.Response(status, json=body)))


@pytest.mark.asyncio
async def test_internal_identity_requires_active_matching_account():
    """密钥正确但账号响应错配时仍拒绝。"""
    headers = {"x-internal-secret": "test-secret", "x-work-user-id": "17"}
    for response_user in [17, 18]:
        transport = httpx.MockTransport(lambda r: httpx.Response(
            200, json={"code": 0, "data": {"userId": response_user}}))
        if response_user == 17:
            identity = await verify_identity(headers, backend_url="http://backend:8080",
                                             internal_secret="test-secret", transport=transport)
            assert identity.headers["X-Work-User-Id"] == "17"
        else:
            with pytest.raises(AuthenticationError):
                await verify_identity(headers, backend_url="http://backend:8080",
                                      internal_secret="test-secret", transport=transport)

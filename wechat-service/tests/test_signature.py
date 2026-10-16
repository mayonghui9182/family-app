"""公众号服务器配置验签（2 用例）。"""
from app.wechat.signature import calc_signature


async def test_valid_signature_returns_echostr(asgi_factory) -> None:  # type: ignore[no-untyped-def]
    client = asgi_factory()
    sig = calc_signature("callback-token", "1234567890", "nonce-abc")
    resp = await client.get(
        "/wechat/callback",
        params={
            "signature": sig,
            "timestamp": "1234567890",
            "nonce": "nonce-abc",
            "echostr": "hello-echo",
        },
    )
    assert resp.status_code == 200
    assert resp.text == "hello-echo"


async def test_invalid_signature_returns_403(asgi_factory) -> None:  # type: ignore[no-untyped-def]
    client = asgi_factory()
    resp = await client.get(
        "/wechat/callback",
        params={
            "signature": "deadbeefdeadbeefdeadbeefdeadbeef",
            "timestamp": "1234567890",
            "nonce": "nonce-abc",
            "echostr": "hello-echo",
        },
    )
    assert resp.status_code == 403

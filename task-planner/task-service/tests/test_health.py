"""task-service 冒烟测试：健康探针可用（tasks.md 1.1 骨架验证）。"""

from fastapi.testclient import TestClient

from src.main import app


def test_healthz_returns_ok() -> None:
    """GET /healthz 返回 200 且标明服务名。"""
    with TestClient(app) as client:
        resp = client.get("/healthz")

    assert resp.status_code == 200
    body = resp.json()
    assert body["status"] == "ok"
    assert body["service"] == "task-service"

"""任务创建与更新接口测试（spec: task-mgmt「创建校验默认值」「五值状态机」两组 Requirement）。"""

import uuid
from datetime import datetime


def _create(client, **overrides) -> dict:
    """创建任务并返回 data 字段的快捷方法。"""
    payload = {"title": "测试任务", **overrides}
    resp = client.post("/api/tasks", json=payload)
    assert resp.status_code == 201, resp.text
    return resp.json()["data"]


async def test_create_minimal_payload_applies_defaults(client):
    """最小请求体：status=pending、priority=medium、source=manual、expires_at=null。"""
    resp = client.post("/api/tasks", json={"title": "写季度总结"})
    assert resp.status_code == 201
    body = resp.json()
    assert body["code"] == 0
    assert body["msg"] == "success"
    data = body["data"]
    assert data["title"] == "写季度总结"
    assert data["status"] == "pending"
    assert data["priority"] == "medium"
    assert data["source"] == "manual"
    assert data["expires_at"] is None
    assert data["user_id"] == "1"


async def test_create_missing_title_rejected(client):
    """缺 title：400，封套指明 title 必填。"""
    resp = client.post("/api/tasks", json={"description": "没有标题"})
    assert resp.status_code == 400
    body = resp.json()
    assert body["code"] != 0
    # 校验明细中必须能定位到 title 字段
    assert "title" in resp.text


async def test_create_blank_title_rejected(client):
    """title 全空白：strip 后非空校验拒绝，400。"""
    resp = client.post("/api/tasks", json={"title": "   "})
    assert resp.status_code == 400
    assert resp.json()["code"] != 0


async def test_create_unknown_category_rejected(client):
    """未知 category_id：400 + 非 0 业务码。"""
    resp = client.post(
        "/api/tasks",
        json={"title": "带分类任务", "category_id": str(uuid.uuid4())},
    )
    assert resp.status_code == 400
    assert resp.json()["code"] != 0


async def test_confirm_draft_moves_to_pending(client):
    """draft→pending 确认：200，状态变更生效。"""
    data = _create(client, title="AI 提案", status="draft")
    resp = client.put(f"/api/tasks/{data['id']}", json={"status": "pending"})
    assert resp.status_code == 200
    assert resp.json()["data"]["status"] == "pending"


async def test_normal_progression_accepted(client):
    """pending→in_progress→completed 链式流转：两次均 200。"""
    data = _create(client)
    resp1 = client.put(f"/api/tasks/{data['id']}", json={"status": "in_progress"})
    assert resp1.status_code == 200
    assert resp1.json()["data"]["status"] == "in_progress"
    resp2 = client.put(f"/api/tasks/{data['id']}", json={"status": "completed"})
    assert resp2.status_code == 200
    assert resp2.json()["data"]["status"] == "completed"


async def test_skipping_states_rejected(client):
    """draft 直跳 completed：400 + 非 0 码（白名单外流转）。"""
    data = _create(client, status="draft")
    resp = client.put(f"/api/tasks/{data['id']}", json={"status": "completed"})
    assert resp.status_code == 400
    assert resp.json()["code"] != 0


async def test_terminal_states_cannot_reopen(client):
    """completed→pending 复活：400 + 非 0 码（终态不可逆）。"""
    data = _create(client)
    client.put(f"/api/tasks/{data['id']}", json={"status": "completed"})
    resp = client.put(f"/api/tasks/{data['id']}", json={"status": "pending"})
    assert resp.status_code == 400
    assert resp.json()["code"] != 0


async def test_update_refreshes_updated_at(client):
    """成功更新后 updated_at 必须晚于更新前的值。"""
    data = _create(client)
    before = datetime.fromisoformat(data["updated_at"])
    resp = client.put(f"/api/tasks/{data['id']}", json={"priority": "high"})
    assert resp.status_code == 200
    after_data = resp.json()["data"]
    assert after_data["priority"] == "high"
    after = datetime.fromisoformat(after_data["updated_at"])
    assert after > before


async def test_update_unknown_task_returns_404(client):
    """更新未知任务：404 + 非 0 码。"""
    resp = client.put(f"/api/tasks/{uuid.uuid4()}", json={"priority": "high"})
    assert resp.status_code == 404
    assert resp.json()["code"] != 0

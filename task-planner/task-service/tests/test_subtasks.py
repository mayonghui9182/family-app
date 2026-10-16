"""子待办接口测试（spec: task-mgmt「Subtask management marks AI-generated origin」）。"""

import uuid

from sqlalchemy import func, select

from src.models.note import Note


def _create_task(client, title: str = "发布会筹备") -> dict:
    """经 REST 建一个任务并返回 data 字段。"""
    resp = client.post("/api/tasks", json={"title": title})
    assert resp.status_code == 201, resp.text
    return resp.json()["data"]


def _create_subtask(client, task_id: str, **overrides) -> dict:
    """经 REST 在指定任务下建子待办并返回 data 字段。"""
    payload = {"title": "订场地", **overrides}
    resp = client.post(f"/api/tasks/{task_id}/subtasks", json=payload)
    assert resp.status_code == 201, resp.text
    return resp.json()["data"]


async def test_manual_subtask_is_not_ai_generated(client):
    """场景1：最小请求体 → 201 + is_ai_generated=false + sort_order=0 + status=pending。"""
    task = _create_task(client)
    resp = client.post(f"/api/tasks/{task['id']}/subtasks", json={"title": "打印材料"})
    assert resp.status_code == 201
    body = resp.json()
    assert body["code"] == 0
    assert body["msg"] == "success"
    data = body["data"]
    assert data["title"] == "打印材料"
    assert data["is_ai_generated"] is False
    assert data["sort_order"] == 0
    assert data["status"] == "pending"


async def test_subtask_status_transition_is_validated(client):
    """场景2：pending→completed 返回 200，再 →draft 被状态机拒绝（400）。"""
    task = _create_task(client)
    sub = _create_subtask(client, task["id"])
    assert sub["status"] == "pending"

    resp1 = client.put(f"/api/subtasks/{sub['id']}", json={"status": "completed"})
    assert resp1.status_code == 200
    assert resp1.json()["data"]["status"] == "completed"

    resp2 = client.put(f"/api/subtasks/{sub['id']}", json={"status": "draft"})
    assert resp2.status_code == 400
    assert resp2.json()["code"] != 0


async def test_delete_subtask_removes_its_notes(client, db):
    """场景3：删除带一条注意事项的子待办 → 200，注意事项不可再查。"""
    task = _create_task(client)
    sub = _create_subtask(client, task["id"], title="确认场地音响")
    note = client.post(
        f"/api/subtasks/{sub['id']}/notes", json={"content": "提前一天调试设备"}
    ).json()["data"]

    resp = client.delete(f"/api/subtasks/{sub['id']}")
    assert resp.status_code == 200
    assert resp.json() == {"code": 0, "msg": "success", "data": None}

    # 库内残留检查：子待办与其注意事项均已物理删除
    note_count = await db.scalar(
        select(func.count()).select_from(Note).where(Note.id == uuid.UUID(note["id"]))
    )
    assert note_count == 0

    # 任务详情中该子待办连同其 notes 一并消失
    detail = client.get(f"/api/tasks/{task['id']}").json()["data"]
    assert detail["subtasks"] == []


async def test_create_subtask_under_unknown_task_returns_404(client):
    """场景4：未知父任务 → 404 + 非 0 业务码。"""
    resp = client.post(f"/api/tasks/{uuid.uuid4()}/subtasks", json={"title": "孤儿子待办"})
    assert resp.status_code == 404
    assert resp.json()["code"] != 0


async def test_update_subtask_fields_preserve_order(client):
    """补充：title/description/sort_order 部分更新生效，显式 sort_order 不被覆盖。"""
    task = _create_task(client)
    sub = _create_subtask(client, task["id"], sort_order=3)

    resp = client.put(
        f"/api/subtasks/{sub['id']}",
        json={"title": "  发送邀请函  ", "description": "含纸质与电子两版"},
    )
    assert resp.status_code == 200
    data = resp.json()["data"]
    assert data["title"] == "发送邀请函"
    assert data["description"] == "含纸质与电子两版"
    # 未传 sort_order 时保持原值（exclude_unset 语义）
    assert data["sort_order"] == 3


async def test_blank_subtask_title_rejected(client):
    """补充：title 全空白 → 400（strip 后非空校验）。"""
    task = _create_task(client)
    resp = client.post(f"/api/tasks/{task['id']}/subtasks", json={"title": "   "})
    assert resp.status_code == 400
    assert resp.json()["code"] != 0


async def test_update_unknown_subtask_returns_404(client):
    """补充：更新未知子待办 → 404。"""
    resp = client.put(f"/api/subtasks/{uuid.uuid4()}", json={"title": "不存在的子待办"})
    assert resp.status_code == 404
    assert resp.json()["code"] != 0


async def test_delete_unknown_subtask_returns_404(client):
    """补充：删除未知子待办 → 404。"""
    resp = client.delete(f"/api/subtasks/{uuid.uuid4()}")
    assert resp.status_code == 404
    assert resp.json()["code"] != 0

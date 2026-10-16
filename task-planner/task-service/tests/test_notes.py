"""注意事项接口测试（spec: task-mgmt「Notes attach to a task or to a subtask」）。"""

import uuid

from sqlalchemy import func, select

from src.models.note import Note


def _create_task(client, title: str = "年会筹备") -> dict:
    """经 REST 建任务并返回 data 字段。"""
    resp = client.post("/api/tasks", json={"title": title})
    assert resp.status_code == 201, resp.text
    return resp.json()["data"]


def _create_subtask(client, task_id: str, title: str = "订场地") -> dict:
    """经 REST 建子待办并返回 data 字段。"""
    resp = client.post(f"/api/tasks/{task_id}/subtasks", json={"title": title})
    assert resp.status_code == 201, resp.text
    return resp.json()["data"]


async def test_task_level_note_created_with_target_type_task(client):
    """场景1：任务级注意事项 → 201 + target_type=task + target_id 为任务 id。"""
    task = _create_task(client)
    resp = client.post(
        f"/api/tasks/{task['id']}/notes", json={"content": "提前2周开始准备"}
    )
    assert resp.status_code == 201
    body = resp.json()
    assert body["code"] == 0
    assert body["msg"] == "success"
    data = body["data"]
    assert data["target_type"] == "task"
    assert data["target_id"] == task["id"]
    assert data["content"] == "提前2周开始准备"


async def test_subtask_level_note_created_with_target_type_subtask(client):
    """场景2：子待办级注意事项 → 201 + target_type=subtask + target_id 为子待办 id。"""
    task = _create_task(client)
    sub = _create_subtask(client, task["id"], title="确认场地音响")
    resp = client.post(
        f"/api/subtasks/{sub['id']}/notes", json={"content": "确认场地音响"}
    )
    assert resp.status_code == 201
    data = resp.json()["data"]
    assert data["target_type"] == "subtask"
    assert data["target_id"] == sub["id"]


async def test_empty_content_is_rejected(client):
    """场景3：content 为空串或全空白 → 400 + 非 0 业务码。"""
    task = _create_task(client)
    resp = client.post(f"/api/tasks/{task['id']}/notes", json={"content": ""})
    assert resp.status_code == 400
    assert resp.json()["code"] != 0

    resp_blank = client.post(f"/api/tasks/{task['id']}/notes", json={"content": "   "})
    assert resp_blank.status_code == 400
    assert resp_blank.json()["code"] != 0


async def test_missing_content_is_rejected(client):
    """补充：请求体缺 content 字段 → 400，且校验明细点名 content。"""
    task = _create_task(client)
    resp = client.post(f"/api/tasks/{task['id']}/notes", json={})
    assert resp.status_code == 400
    assert resp.json()["code"] != 0
    assert "content" in resp.text


async def test_unknown_targets_return_404(client):
    """补充：未知任务/未知子待办作为目标 → 404。"""
    task_resp = client.post(
        f"/api/tasks/{uuid.uuid4()}/notes", json={"content": "挂在不存在任务上"}
    )
    assert task_resp.status_code == 404
    assert task_resp.json()["code"] != 0

    sub_resp = client.post(
        f"/api/subtasks/{uuid.uuid4()}/notes", json={"content": "挂在不存在子待办上"}
    )
    assert sub_resp.status_code == 404
    assert sub_resp.json()["code"] != 0


async def test_delete_note_is_physical(client, db):
    """补充：DELETE /api/notes/{id} → 200 且库内无残留，重复删除 404。"""
    task = _create_task(client)
    note = client.post(
        f"/api/tasks/{task['id']}/notes", json={"content": "预算需财务复核"}
    ).json()["data"]

    resp = client.delete(f"/api/notes/{note['id']}")
    assert resp.status_code == 200
    assert resp.json() == {"code": 0, "msg": "success", "data": None}

    assert (
        await db.scalar(
            select(func.count()).select_from(Note).where(Note.id == uuid.UUID(note["id"]))
        )
        == 0
    )

    again = client.delete(f"/api/notes/{note['id']}")
    assert again.status_code == 404
    assert again.json()["code"] != 0


async def test_notes_grouped_by_level_in_task_detail(client):
    """补充：详情聚合中任务级与子待办级注意事项各归其位。"""
    task = _create_task(client)
    sub = _create_subtask(client, task["id"], title="发邀请")
    client.post(f"/api/tasks/{task['id']}/notes", json={"content": "提前两周启动"})
    client.post(f"/api/subtasks/{sub['id']}/notes", json={"content": "名单需去重"})

    detail = client.get(f"/api/tasks/{task['id']}").json()["data"]
    assert [n["content"] for n in detail["notes"]] == ["提前两周启动"]
    assert len(detail["subtasks"]) == 1
    assert [n["content"] for n in detail["subtasks"][0]["notes"]] == ["名单需去重"]

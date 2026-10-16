"""draft 生命周期与任务删除测试（spec: task-mgmt「24h 过期」「物理删除级联」两组 Requirement）。"""

import uuid
from datetime import datetime, timedelta

from sqlalchemy import func, select

from src.models.note import Note
from src.models.subtask import Subtask
from src.models.task import Task


async def test_draft_carries_24h_expiry(client):
    """draft 创建：expires_at = 创建时间 + 24h（容差 1 分钟）。"""
    resp = client.post("/api/tasks", json={"title": "AI 拆解提案", "status": "draft"})
    assert resp.status_code == 201
    data = resp.json()["data"]
    assert data["status"] == "draft"
    created = datetime.fromisoformat(data["created_at"])
    expires = datetime.fromisoformat(data["expires_at"])
    assert abs(expires - created - timedelta(hours=24)) < timedelta(minutes=1)


async def test_confirmation_clears_expiry(client):
    """draft→pending 确认：expires_at 被清空（响应与库内均为 null）。"""
    draft = client.post(
        "/api/tasks", json={"title": "待确认提案", "status": "draft"}
    ).json()["data"]
    assert draft["expires_at"] is not None

    resp = client.put(f"/api/tasks/{draft['id']}", json={"status": "pending"})
    assert resp.status_code == 200
    assert resp.json()["data"]["expires_at"] is None

    detail = client.get(f"/api/tasks/{draft['id']}").json()["data"]
    assert detail["status"] == "pending"
    assert detail["expires_at"] is None


async def test_non_draft_never_carries_expiry(client):
    """未指定 status 创建（默认 pending）：expires_at 恒为 null。"""
    resp = client.post("/api/tasks", json={"title": "普通任务"})
    assert resp.status_code == 201
    assert resp.json()["data"]["expires_at"] is None


async def test_delete_cascades_subtasks_and_notes(client, db):
    """物理删除：任务、子待办、两级 notes 全部不可再查。"""
    task = Task(title="发布会筹备")
    db.add(task)
    await db.flush()
    sub_a = Subtask(parent_task_id=task.id, title="订场地", sort_order=1)
    sub_b = Subtask(parent_task_id=task.id, title="发邀请", sort_order=2)
    db.add_all([sub_a, sub_b])
    await db.flush()
    db.add(Note(target_type="task", target_id=task.id, content="提前两周启动"))
    db.add(Note(target_type="subtask", target_id=sub_a.id, content="确认预算"))
    await db.commit()
    task_id = task.id

    resp = client.delete(f"/api/tasks/{task_id}")
    assert resp.status_code == 200
    assert resp.json() == {"code": 0, "msg": "success", "data": None}

    assert client.get(f"/api/tasks/{task_id}").status_code == 404

    # 库内残留检查：任务/子待办/两级 notes 均已物理删除
    assert await db.scalar(select(func.count()).select_from(Task)) == 0
    assert await db.scalar(select(func.count()).select_from(Subtask)) == 0
    assert await db.scalar(select(func.count()).select_from(Note)) == 0


async def test_cancelled_tasks_are_retained(client):
    """cancelled 任务不自动清除，仍可按状态查询（学习素材）。"""
    data = client.post("/api/tasks", json={"title": "被取消的任务"}).json()["data"]
    resp = client.put(f"/api/tasks/{data['id']}", json={"status": "cancelled"})
    assert resp.status_code == 200
    assert resp.json()["data"]["status"] == "cancelled"

    listing = client.get("/api/tasks", params={"status": "cancelled"})
    assert listing.status_code == 200
    assert data["id"] in {t["id"] for t in listing.json()["data"]}

    # 详情同样可查
    assert client.get(f"/api/tasks/{data['id']}").status_code == 200


async def test_delete_unknown_task_returns_404(client):
    """删除未知任务：404 + 非 0 码。"""
    resp = client.delete(f"/api/tasks/{uuid.uuid4()}")
    assert resp.status_code == 404
    assert resp.json()["code"] != 0

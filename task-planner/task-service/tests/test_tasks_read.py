"""任务列表与详情接口测试（spec: task-mgmt「过滤检索」「详情聚合」两组 Requirement）。"""

import uuid
from datetime import UTC, datetime, timedelta

from src.models.category import Category
from src.models.note import Note
from src.models.subtask import Subtask
from src.models.task import Task


async def test_empty_list_returns_empty_envelope(client):
    """空库列表：封套逐字等于 {"code":0,"msg":"success","data":[]}。"""
    resp = client.get("/api/tasks")
    assert resp.status_code == 200
    assert resp.json() == {"code": 0, "msg": "success", "data": []}


async def test_filter_by_status(client):
    """status 过滤：只返回匹配状态的任务。"""
    client.post("/api/tasks", json={"title": "待办任务"})
    task_id = client.post("/api/tasks", json={"title": "进行中任务"}).json()["data"]["id"]
    client.put(f"/api/tasks/{task_id}", json={"status": "in_progress"})
    done_id = client.post("/api/tasks", json={"title": "已完成任务"}).json()["data"]["id"]
    client.put(f"/api/tasks/{done_id}", json={"status": "completed"})

    resp = client.get("/api/tasks", params={"status": "pending"})
    assert resp.status_code == 200
    data = resp.json()["data"]
    assert [t["title"] for t in data] == ["待办任务"]
    assert all(t["status"] == "pending" for t in data)


async def test_filters_combine(client, db):
    """status + category + due_before 三条件组合：全部满足才返回。"""
    category = Category(name="工作")
    db.add(category)
    await db.flush()

    matched = Task(
        title="匹配任务", status="pending", category_id=category.id,
        due_date=datetime(2026, 9, 25, tzinfo=UTC),
    )
    late = Task(
        title="截止太晚", status="pending", category_id=category.id,
        due_date=datetime(2026, 10, 5, tzinfo=UTC),
    )
    wrong_status = Task(
        title="状态不符", status="in_progress", category_id=category.id,
        due_date=datetime(2026, 9, 25, tzinfo=UTC),
    )
    db.add_all([matched, late, wrong_status])
    await db.commit()

    resp = client.get(
        "/api/tasks",
        params={
            "status": "pending",
            "category": str(category.id),
            "due_before": "2026-09-30T00:00:00Z",
        },
    )
    assert resp.status_code == 200
    data = resp.json()["data"]
    assert [t["title"] for t in data] == ["匹配任务"]


async def test_keyword_matches_title_or_description(client):
    """keyword 同时匹配标题与描述。"""
    client.post("/api/tasks", json={"title": "准备产品发布会"})
    client.post("/api/tasks", json={"title": "采购物资", "description": "用于发布会现场"})
    client.post("/api/tasks", json={"title": "无关任务"})

    resp = client.get("/api/tasks", params={"keyword": "发布会"})
    assert resp.status_code == 200
    titles = {t["title"] for t in resp.json()["data"]}
    assert titles == {"准备产品发布会", "采购物资"}


async def test_expired_draft_hidden_from_list(client, db):
    """过期 draft 一律不出现在列表（谓词 status != 'draft' OR expires_at > now）。"""
    draft_id = client.post(
        "/api/tasks", json={"title": "过期提案", "status": "draft"}
    ).json()["data"]["id"]
    normal_id = client.post("/api/tasks", json={"title": "正常任务"}).json()["data"]["id"]

    draft = await db.get(Task, uuid.UUID(draft_id))
    draft.expires_at = datetime.now(UTC) - timedelta(hours=1)
    await db.commit()

    resp = client.get("/api/tasks")
    ids = {t["id"] for t in resp.json()["data"]}
    assert draft_id not in ids
    assert normal_id in ids


async def test_detail_orders_subtasks_and_carries_notes(client, db):
    """详情聚合：子待办按 sort_order 升序；每个子待办携带自己的 notes。"""
    task = Task(title="带子待办的任务")
    db.add(task)
    await db.flush()

    second = Subtask(parent_task_id=task.id, title="第二步", sort_order=2)
    first = Subtask(parent_task_id=task.id, title="第一步", sort_order=1)
    db.add_all([second, first])
    await db.flush()
    db.add(Note(target_type="subtask", target_id=first.id, content="先打印材料"))
    db.add(Note(target_type="task", target_id=task.id, content="任务级提醒"))
    await db.commit()

    resp = client.get(f"/api/tasks/{task.id}")
    assert resp.status_code == 200
    body = resp.json()
    assert body["code"] == 0
    data = body["data"]
    assert [st["sort_order"] for st in data["subtasks"]] == [1, 2]
    assert len(data["subtasks"][0]["notes"]) == 1
    assert data["subtasks"][0]["notes"][0]["content"] == "先打印材料"
    assert data["subtasks"][1]["notes"] == []
    assert [n["content"] for n in data["notes"]] == ["任务级提醒"]


async def test_detail_unknown_id_returns_404(client):
    """未知任务 id：404 + 非 0 业务码。"""
    resp = client.get(f"/api/tasks/{uuid.uuid4()}")
    assert resp.status_code == 404
    body = resp.json()
    assert body["code"] != 0
    assert body["msg"]

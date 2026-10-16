"""双账号验证工作数据及关联引用隔离，包含真实数据库读写。"""

from sqlalchemy import select, update
import pytest

from src.models import Task, Category
from src.ownership import owner_id


def test_two_users_cannot_read_or_mutate_each_others_task(client):
    first = client.post("/api/tasks", json={"title": "账号一的私人工作"}).json()["data"]
    second_headers = {"X-Access-Token": "b" * 64}
    assert client.get("/api/tasks", headers=second_headers).json()["data"] == []
    path = "/api/tasks/" + first["id"]
    assert client.get(path, headers=second_headers).status_code == 404
    assert client.put(path, headers=second_headers, json={"title": "被修改"}).status_code == 404
    assert client.delete(path, headers=second_headers).status_code == 404
    assert client.get(path).json()["data"]["title"] == "账号一的私人工作"


def test_category_reference_cannot_cross_users(client):
    category = client.post("/api/categories", json={"name": "私人分类"}).json()["data"]
    headers = {"X-Access-Token": "b" * 64}
    assert client.post("/api/tasks", headers=headers, json={
        "title": "越权引用", "category_id": category["id"]}).status_code == 400
    own = client.post("/api/categories", headers=headers, json={"name": "私人分类"})
    assert own.status_code == 201


@pytest.mark.asyncio
async def test_bulk_update_and_identity_switch_do_not_escape_scope(session):
    row = Task(title="私人", status="pending")
    session.add(row)
    await session.commit()
    task_id = row.id
    token = owner_id.set("2")
    try:
        async with type(session)(session.bind, expire_on_commit=False) as other:
            assert await other.get(Task, task_id) is None
            result = await other.execute(update(Task).values(title="串用户"))
            assert result.rowcount == 0
            await other.commit()
    finally:
        owner_id.reset(token)
    await session.refresh(row)
    assert row.title == "私人"

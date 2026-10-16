"""分类接口测试（spec: task-mgmt「Category management with unique names and merge」）。"""

import uuid

from sqlalchemy import select

from src.models.category import RESERVED_CATEGORY_NAME, Category
from src.models.rule_template import RuleTemplate


def _create_category(client, name: str, description: str | None = None) -> dict:
    """经 REST 建分类并返回 data 字段。"""
    payload = {"name": name}
    if description is not None:
        payload["description"] = description
    resp = client.post("/api/categories", json=payload)
    assert resp.status_code == 201, resp.text
    return resp.json()["data"]


def _category_names(client) -> list[str]:
    """取分类列表的名称序列（保持接口返回顺序）。"""
    resp = client.get("/api/categories")
    assert resp.status_code == 200, resp.text
    return [c["name"] for c in resp.json()["data"]]


async def test_list_categories_ordered_by_created_at_asc(client):
    """补充：列表按创建时间升序返回，封套形态固定。"""
    _create_category(client, "市场推广")
    _create_category(client, "产品研发")
    _create_category(client, "行政事务")

    resp = client.get("/api/categories")
    assert resp.status_code == 200
    body = resp.json()
    assert body["code"] == 0
    assert body["msg"] == "success"
    assert [c["name"] for c in body["data"]] == ["市场推广", "产品研发", "行政事务"]


async def test_duplicate_category_name_is_rejected(client):
    """场景1：已存在「市场推广」时再建同名 → 400 且业务码非 0。"""
    _create_category(client, "市场推广")
    resp = client.post("/api/categories", json={"name": "市场推广"})
    assert resp.status_code == 400
    assert resp.json()["code"] != 0
    # 名称未被重复写入
    assert _category_names(client).count("市场推广") == 1


async def test_blank_and_missing_name_rejected(client):
    """补充：名称缺失或全空白 → 400。"""
    assert client.post("/api/categories", json={"name": "   "}).status_code == 400
    assert client.post("/api/categories", json={}).status_code == 400


async def test_rename_category(client):
    """补充：重命名生效，改成他人已有名称 → 400，改成自身当前名称 → 200。"""
    target = _create_category(client, "市场推广")
    _create_category(client, "产品研发")

    resp = client.put(
        f"/api/categories/{target['id']}", json={"name": " 品牌活动 ", "description": "线下活动"}
    )
    assert resp.status_code == 200
    data = resp.json()["data"]
    assert data["name"] == "品牌活动"
    assert data["description"] == "线下活动"

    dup = client.put(f"/api/categories/{target['id']}", json={"name": "产品研发"})
    assert dup.status_code == 400
    assert dup.json()["code"] != 0

    same = client.put(f"/api/categories/{target['id']}", json={"name": "品牌活动"})
    assert same.status_code == 200


async def test_update_unknown_category_returns_404(client):
    """补充：重命名未知分类 → 404。"""
    resp = client.put(f"/api/categories/{uuid.uuid4()}", json={"name": "不存在的分类"})
    assert resp.status_code == 404
    assert resp.json()["code"] != 0


async def test_merge_reassigns_tasks_and_templates(client, db):
    """场景2：合并后两个任务与模板均改挂目标分类，源分类从列表消失。"""
    source = _create_category(client, "市场推广")
    target = _create_category(client, "品牌活动")

    task_a = client.post(
        "/api/tasks", json={"title": "春季发布会", "category_id": source["id"]}
    ).json()["data"]
    task_b = client.post(
        "/api/tasks", json={"title": "线下路演", "category_id": source["id"]}
    ).json()["data"]

    template = RuleTemplate(
        type="decomposition",
        name="发布会拆解模板",
        category_id=uuid.UUID(source["id"]),
        content={
            "common_steps": ["订场地", "发邀请", "现场布置"],
            "estimated_subtask_count": 5,
            "notes": "提前两周启动",
        },
    )
    db.add(template)
    await db.commit()
    template_id = template.id

    resp = client.post(
        f"/api/categories/{source['id']}/merge",
        json={"target_category_id": target["id"]},
    )
    assert resp.status_code == 200
    assert resp.json()["code"] == 0
    assert resp.json()["data"]["id"] == target["id"]

    # 任务改挂：详情中的 category_id 与派生 category_name 均指向目标分类
    db.expire_all()
    for task in (task_a, task_b):
        detail = client.get(f"/api/tasks/{task['id']}").json()["data"]
        assert detail["category_id"] == target["id"]
        assert detail["category_name"] == "品牌活动"

    # 模板改挂：直接查库内列值
    merged_category_id = await db.scalar(
        select(RuleTemplate.category_id).where(RuleTemplate.id == template_id)
    )
    assert merged_category_id == uuid.UUID(target["id"])

    # 源分类已删除，目标分类保留
    names = _category_names(client)
    assert "市场推广" not in names
    assert "品牌活动" in names
    assert await db.scalar(
        select(Category.id).where(Category.id == uuid.UUID(source["id"]))
    ) is None


async def test_merge_into_itself_is_rejected(client):
    """场景3：自合并 → 400 + 非 0 业务码，分类仍然存在。"""
    source = _create_category(client, "市场推广")
    resp = client.post(
        f"/api/categories/{source['id']}/merge",
        json={"target_category_id": source["id"]},
    )
    assert resp.status_code == 400
    assert resp.json()["code"] != 0
    assert "市场推广" in _category_names(client)


async def test_merge_into_unknown_target_is_rejected(client):
    """补充：目标分类不存在 → 400（按 spec 归为 400 而非 404），源分类保留。"""
    source = _create_category(client, "市场推广")
    resp = client.post(
        f"/api/categories/{source['id']}/merge",
        json={"target_category_id": str(uuid.uuid4())},
    )
    assert resp.status_code == 400
    assert resp.json()["code"] != 0
    assert "市场推广" in _category_names(client)


async def test_merge_unknown_source_returns_404(client):
    """补充：源分类不存在 → 404。"""
    target = _create_category(client, "品牌活动")
    resp = client.post(
        f"/api/categories/{uuid.uuid4()}/merge",
        json={"target_category_id": target["id"]},
    )
    assert resp.status_code == 404
    assert resp.json()["code"] != 0


async def test_reserved_category_cannot_be_merged_away(client, db):
    """补充：保留分类「未分类」是 AI 归类兜底，不得被合并删除。"""
    reserved = _create_category(client, RESERVED_CATEGORY_NAME)
    target = _create_category(client, "品牌活动")

    resp = client.post(
        f"/api/categories/{reserved['id']}/merge",
        json={"target_category_id": target["id"]},
    )
    assert resp.status_code == 400
    assert resp.json()["code"] != 0
    assert RESERVED_CATEGORY_NAME in _category_names(client)

    # 反向合并（其他分类并入「未分类」）是允许的
    ok_resp = client.post(
        f"/api/categories/{target['id']}/merge",
        json={"target_category_id": reserved["id"]},
    )
    assert ok_resp.status_code == 200
    assert "品牌活动" not in _category_names(client)

"""模板接口测试（spec: template-learning「Decomposition rule template persistence and retrieval」）。"""

import uuid

from sqlalchemy import select

from src.models.rule_template import RuleTemplate


def _create_category(client, name: str) -> dict:
    """经 REST 建分类并返回 data 字段。"""
    resp = client.post("/api/categories", json={"name": name})
    assert resp.status_code == 201, resp.text
    return resp.json()["data"]


def _create_template(client, name: str = "季度汇报拆解", **overrides) -> dict:
    """经 REST 建模板并返回 data 字段（默认 decomposition + 三步内容）。"""
    payload = {
        "name": name,
        "type": "decomposition",
        "common_steps": ["确定主题", "制作PPT", "彩排"],
        "estimated_subtask_count": 4,
    }
    payload.update(overrides)
    resp = client.post("/api/templates", json=payload)
    assert resp.status_code == 201, resp.text
    return resp.json()["data"]


async def test_templates_filtered_by_category_and_type(client):
    """场景1：两个分类各有 decomposition 模板 + 一个 routine 模板，
    GET ?type=decomposition&category=<uuid> 只返回该分类的 decomposition 模板。"""
    cat_a = _create_category(client, "市场推广")
    cat_b = _create_category(client, "工作汇报")
    tpl_a = _create_template(client, "发布会拆解", category_id=cat_a["id"])
    _create_template(client, "汇报拆解", category_id=cat_b["id"])
    _create_template(
        client, "每周例会", type="routine", category_id=cat_a["id"]
    )

    resp = client.get(
        f"/api/templates?type=decomposition&category={cat_a['id']}"
    )
    assert resp.status_code == 200
    body = resp.json()
    assert body["code"] == 0
    assert body["msg"] == "success"
    assert [t["id"] for t in body["data"]] == [tpl_a["id"]]
    assert body["data"][0]["category_name"] == "市场推广"


async def test_template_content_follows_decomposition_shape(client, db):
    """场景2：创建后存储 content 逐字等于三字段形态，version=1、is_active=true。"""
    created = _create_template(client)
    assert created["version"] == 1
    assert created["is_active"] is True
    assert created["content"] == {
        "common_steps": ["确定主题", "制作PPT", "彩排"],
        "estimated_subtask_count": 4,
        "notes": "",
    }
    assert created["conditions"] is None

    # 库内列值与响应一致（JSONB 落盘形态）
    db.expire_all()
    stored = await db.scalar(
        select(RuleTemplate.content).where(RuleTemplate.id == uuid.UUID(created["id"]))
    )
    assert stored == {
        "common_steps": ["确定主题", "制作PPT", "彩排"],
        "estimated_subtask_count": 4,
        "notes": "",
    }


async def test_create_with_unknown_category_rejected(client):
    """补充：未知分类 id → 400（与任务创建契约一致）。"""
    resp = client.post(
        "/api/templates",
        json={"name": "孤儿模板", "category_id": str(uuid.uuid4())},
    )
    assert resp.status_code == 400
    assert resp.json()["code"] != 0


async def test_create_with_invalid_type_rejected(client):
    """补充：type 不在登记集合内 → 400 参数校验失败。"""
    resp = client.post("/api/templates", json={"name": "非法类型", "type": "unknown"})
    assert resp.status_code == 400
    assert resp.json()["code"] == 40099


async def test_routine_template_stored_but_excluded_from_decomposition(client):
    """场景3：routine 模板可存储，但不出现在 decomposition 匹配候选中。"""
    category = _create_category(client, "行政事务")
    routine = _create_template(
        client, "每周例会", type="routine", category_id=category["id"]
    )

    decomposition = client.get(
        f"/api/templates?type=decomposition&category={category['id']}"
    ).json()["data"]
    assert routine["id"] not in [t["id"] for t in decomposition]

    # routine 本身可按 type 过滤查到（只存不评估）
    routines = client.get(
        f"/api/templates?type=routine&category={category['id']}"
    ).json()["data"]
    assert [t["id"] for t in routines] == [routine["id"]]


async def test_deactivated_template_excluded_from_candidates(client):
    """场景（is_active=false 排除候选）：停用后候选列表不含该模板，
    历史保留、可重新启用。"""
    category = _create_category(client, "市场推广")
    template = _create_template(client, category_id=category["id"])

    stop = client.put(
        f"/api/templates/{template['id']}", json={"is_active": False}
    )
    assert stop.status_code == 200
    assert stop.json()["data"]["is_active"] is False

    candidates = client.get(
        f"/api/templates?type=decomposition&category={category['id']}&is_active=true"
    ).json()["data"]
    assert template["id"] not in [t["id"] for t in candidates]

    # 管理视图（不带 is_active 过滤）仍可见，版本历史保留
    all_templates = client.get(
        f"/api/templates?type=decomposition&category={category['id']}"
    ).json()["data"]
    assert template["id"] in [t["id"] for t in all_templates]
    versions = client.get(f"/api/templates/{template['id']}/versions")
    assert versions.status_code == 200
    assert len(versions.json()["data"]) >= 1

    # 重新启用后回到候选
    client.put(f"/api/templates/{template['id']}", json={"is_active": True})
    candidates = client.get(
        f"/api/templates?type=decomposition&category={category['id']}&is_active=true"
    ).json()["data"]
    assert template["id"] in [t["id"] for t in candidates]


async def test_edit_content_replaces_stored_fields(client):
    """补充：编辑 content 三字段后响应与库内值同步更新。"""
    template = _create_template(client)
    resp = client.put(
        f"/api/templates/{template['id']}",
        json={
            "common_steps": ["确定主题", "邀请媒体"],
            "estimated_subtask_count": 6,
            "notes": "预留两周缓冲",
        },
    )
    assert resp.status_code == 200
    assert resp.json()["data"]["content"] == {
        "common_steps": ["确定主题", "邀请媒体"],
        "estimated_subtask_count": 6,
        "notes": "预留两周缓冲",
    }


async def test_rename_does_not_bump_version(client):
    """补充：仅改名 / 仅切换 is_active 都不产生新版本（spec 只约束 content 变更）。"""
    template = _create_template(client)
    resp = client.put(f"/api/templates/{template['id']}", json={"name": " 发布会拆解V2 "})
    assert resp.status_code == 200
    assert resp.json()["data"]["name"] == "发布会拆解V2"
    assert resp.json()["data"]["version"] == 1

    versions = client.get(f"/api/templates/{template['id']}/versions").json()["data"]
    assert len(versions) == 1


async def test_unknown_template_returns_404(client):
    """补充：编辑未知模板 / 查未知模板版本历史 → 404 + TEMPLATE_NOT_FOUND。"""
    unknown = str(uuid.uuid4())
    put_resp = client.put(f"/api/templates/{unknown}", json={"notes": "x"})
    assert put_resp.status_code == 404
    assert put_resp.json()["code"] == 40404

    ver_resp = client.get(f"/api/templates/{unknown}/versions")
    assert ver_resp.status_code == 404
    assert ver_resp.json()["code"] == 40404

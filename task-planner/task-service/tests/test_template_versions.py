"""模板版本历史测试（spec: template-learning「Template changes create version history」与手动编辑契约）。"""

import uuid

from sqlalchemy import select

from src.models.rule_template import RuleTemplate, RuleTemplateVersion


def _create_template(client, name: str = "季度汇报拆解") -> dict:
    """经 REST 建 decomposition 模板并返回 data 字段。"""
    resp = client.post(
        "/api/templates",
        json={
            "name": name,
            "type": "decomposition",
            "common_steps": ["确定主题", "制作PPT", "彩排"],
            "estimated_subtask_count": 4,
        },
    )
    assert resp.status_code == 201, resp.text
    return resp.json()["data"]


def _edit(client, template_id: str, **fields) -> dict:
    """经 REST 编辑模板并返回 data 字段。"""
    resp = client.put(f"/api/templates/{template_id}", json=fields)
    assert resp.status_code == 200, resp.text
    return resp.json()["data"]


async def _rows_by_version(db, template_id: str) -> dict[int, RuleTemplateVersion]:
    """查库取全部版本行，按 version 建索引。"""
    db.expire_all()
    rows = (
        await db.execute(
            select(RuleTemplateVersion).where(
                RuleTemplateVersion.template_id == uuid.UUID(template_id)
            )
        )
    ).scalars().all()
    return {row.version: row for row in rows}


async def test_creation_writes_version_one_row(client, db):
    """补充：创建即写版本 1 快照行，parent_version_id 为空。"""
    template = _create_template(client)
    rows = await _rows_by_version(db, template["id"])
    assert set(rows) == {1}
    assert rows[1].content == template["content"]
    assert rows[1].parent_version_id is None
    assert rows[1].change_summary == "创建模板"
    assert rows[1].is_important is False


async def test_update_snapshots_new_version(client, db):
    """场景1：版本 3 的模板收到更新 → 主表 version=4，
    新行 content 为更新后内容、parent_version_id 指向版本 3 行。"""
    template = _create_template(client)
    _edit(client, template["id"], estimated_subtask_count=5)  # → v2
    _edit(client, template["id"], notes="第一版备注")          # → v3

    updated = _edit(
        client, template["id"], common_steps=["确定主题", "制作PPT", "彩排", "物料准备"]
    )  # → v4
    assert updated["version"] == 4

    rows = await _rows_by_version(db, template["id"])
    assert set(rows) == {1, 2, 3, 4}
    assert rows[4].content == updated["content"]
    assert rows[4].parent_version_id == rows[3].id
    # expire_all 会让 ORM 实例过期，先取出版本 3 行 id，避免后续同步懒加载
    v3_row_id = rows[3].id

    # 主表列值：version=4，parent_version_id 指向版本 3 行
    db.expire_all()
    stored_version = await db.scalar(
        select(RuleTemplate.version).where(
            RuleTemplate.id == uuid.UUID(template["id"])
        )
    )
    stored_parent = await db.scalar(
        select(RuleTemplate.parent_version_id).where(
            RuleTemplate.id == uuid.UUID(template["id"])
        )
    )
    assert stored_version == 4
    assert stored_parent == v3_row_id


async def test_version_history_listed_newest_first(client):
    """场景2：版本 1..4 存在时，GET versions 返回版本 4 在前、版本 1 在后。"""
    template = _create_template(client)
    _edit(client, template["id"], estimated_subtask_count=5)   # v2
    _edit(client, template["id"], notes="备注一")               # v3
    _edit(client, template["id"], estimated_subtask_count=6)   # v4

    resp = client.get(f"/api/templates/{template['id']}/versions")
    assert resp.status_code == 200
    body = resp.json()
    assert body["code"] == 0
    versions = [v["version"] for v in body["data"]]
    assert versions == [4, 3, 2, 1]
    assert versions[0] == 4 and versions[-1] == 1


async def test_manual_edit_records_fixed_change_summary(client, db):
    """场景（手动编辑契约）：编辑 common_steps → 新行 change_summary
    逐字为「用户手动编辑」，且行 created_at 即手动编辑时间（供当天冲突判定）。"""
    template = _create_template(client)
    _edit(client, template["id"], common_steps=["确定主题", "邀请媒体"])

    rows = await _rows_by_version(db, template["id"])
    assert rows[2].change_summary == "用户手动编辑"
    assert rows[2].created_at is not None


async def test_is_active_toggle_creates_no_version(client, db):
    """补充：仅切换 is_active 不改 content → 不产生新版本行。"""
    template = _create_template(client)
    stopped = _edit(client, template["id"], is_active=False)
    assert stopped["is_active"] is False
    assert stopped["version"] == 1

    rows = await _rows_by_version(db, template["id"])
    assert set(rows) == {1}


async def test_noop_content_update_creates_no_version(client, db):
    """补充：提交与当前完全相同的 content → 版本不变、不写新行。"""
    template = _create_template(client)
    same = _edit(
        client,
        template["id"],
        common_steps=["确定主题", "制作PPT", "彩排"],
        estimated_subtask_count=4,
        notes="",
    )
    assert same["version"] == 1

    rows = await _rows_by_version(db, template["id"])
    assert set(rows) == {1}


async def test_mark_version_important(client, db):
    """场景（重要标记接口）：设置 is_important=true 后库内列值同步。"""
    template = _create_template(client)
    _edit(client, template["id"], notes="关键版本")  # v2

    versions = client.get(f"/api/templates/{template['id']}/versions").json()["data"]
    target = next(v for v in versions if v["version"] == 1)

    resp = client.put(
        f"/api/templates/{template['id']}/versions/{target['id']}",
        json={"is_important": True},
    )
    assert resp.status_code == 200
    assert resp.json()["data"]["is_important"] is True

    db.expire_all()
    stored = await db.scalar(
        select(RuleTemplateVersion.is_important).where(
            RuleTemplateVersion.id == uuid.UUID(target["id"])
        )
    )
    assert stored is True


async def test_mark_unknown_or_foreign_version_returns_404(client):
    """补充：未知版本行、或版本行属于别的模板 → 404。"""
    template = _create_template(client)
    other = _create_template(client, name="另一个模板")

    resp = client.put(
        f"/api/templates/{template['id']}/versions/{uuid.uuid4()}",
        json={"is_important": True},
    )
    assert resp.status_code == 404

    other_versions = client.get(
        f"/api/templates/{other['id']}/versions"
    ).json()["data"]
    foreign = client.put(
        f"/api/templates/{template['id']}/versions/{other_versions[0]['id']}",
        json={"is_important": True},
    )
    assert foreign.status_code == 404

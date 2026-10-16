"""版本保留策略测试（spec: template-learning「bounded retention」，每模板最多 20 版）。"""

import uuid

from sqlalchemy import func, select, update

from src.models.rule_template import MAX_TEMPLATE_VERSIONS, RuleTemplate, RuleTemplateVersion


def _create_template(client) -> dict:
    """经 REST 建 decomposition 模板（版本 1）。"""
    resp = client.post(
        "/api/templates",
        json={
            "name": "季度汇报拆解",
            "type": "decomposition",
            "common_steps": ["确定主题", "制作PPT", "彩排"],
            "estimated_subtask_count": 4,
        },
    )
    assert resp.status_code == 201, resp.text
    return resp.json()["data"]


async def _seed_history(db, template_id: str, up_to: int = MAX_TEMPLATE_VERSIONS) -> None:
    """直插版本 2..up_to 的历史行，并把模板当前版本号推到 up_to。"""
    for version in range(2, up_to + 1):
        db.add(
            RuleTemplateVersion(
                template_id=uuid.UUID(template_id),
                version=version,
                content={
                    "common_steps": [f"步骤{version}"],
                    "estimated_subtask_count": version,
                    "notes": "",
                },
                change_summary=f"历史版本{version}",
                is_important=False,
            )
        )
    template = await db.get(RuleTemplate, uuid.UUID(template_id))
    template.version = up_to
    await db.commit()


async def _version_stats(db, template_id: str) -> tuple[int, int | None, int | None]:
    """返回（行数, 最小版本号, 最大版本号）。"""
    db.expire_all()
    row = (
        await db.execute(
            select(
                func.count(),
                func.min(RuleTemplateVersion.version),
                func.max(RuleTemplateVersion.version),
            ).where(RuleTemplateVersion.template_id == uuid.UUID(template_id))
        )
    ).one()
    return row[0], row[1], row[2]


async def test_retention_deletes_oldest_beyond_twenty(client, db):
    """场景1：第 21 版产生且无重要标记 → 最老版本行被删，恰剩 20 行。"""
    template = _create_template(client)
    await _seed_history(db, template["id"], up_to=20)

    resp = client.put(
        f"/api/templates/{template['id']}", json={"estimated_subtask_count": 99}
    )
    assert resp.status_code == 200
    assert resp.json()["data"]["version"] == 21

    count, min_version, max_version = await _version_stats(db, template["id"])
    assert count == MAX_TEMPLATE_VERSIONS
    assert min_version == 2  # 版本 1（最老的非重要行）被淘汰
    assert max_version == 21

    # 被淘汰行的悬挂引用已断开：剩余行的 parent_version_id 不指向已删行
    db.expire_all()
    orphaned = (
        await db.execute(
            select(RuleTemplateVersion.parent_version_id).where(
                RuleTemplateVersion.template_id == uuid.UUID(template["id"]),
                RuleTemplateVersion.parent_version_id.is_not(None),
            )
        )
    ).scalars().all()
    existing_ids = set(
        (
            await db.execute(
                select(RuleTemplateVersion.id).where(
                    RuleTemplateVersion.template_id == uuid.UUID(template["id"])
                )
            )
        ).scalars().all()
    )
    assert all(parent_id in existing_ids for parent_id in orphaned)


async def test_important_version_survives_retention(client, db):
    """场景2：版本 1 行 is_important=true → 第 21 版产生后版本 1 仍在，
    被删的是最老的非重要行（版本 2），仍恰剩 20 行。"""
    template = _create_template(client)
    await _seed_history(db, template["id"], up_to=20)

    # 通过重要标记接口保护版本 1
    versions = client.get(f"/api/templates/{template['id']}/versions").json()["data"]
    v1 = next(v for v in versions if v["version"] == 1)
    mark = client.put(
        f"/api/templates/{template['id']}/versions/{v1['id']}",
        json={"is_important": True},
    )
    assert mark.status_code == 200

    resp = client.put(
        f"/api/templates/{template['id']}", json={"estimated_subtask_count": 99}
    )
    assert resp.json()["data"]["version"] == 21

    count, min_version, max_version = await _version_stats(db, template["id"])
    assert count == MAX_TEMPLATE_VERSIONS
    assert min_version == 1  # 重要版本存活
    assert max_version == 21

    db.expire_all()
    v1_alive = await db.scalar(
        select(RuleTemplateVersion.is_important).where(
            RuleTemplateVersion.template_id == uuid.UUID(template["id"]),
            RuleTemplateVersion.version == 1,
        )
    )
    assert v1_alive is True
    v2_alive = await db.scalar(
        select(RuleTemplateVersion.id).where(
            RuleTemplateVersion.template_id == uuid.UUID(template["id"]),
            RuleTemplateVersion.version == 2,
        )
    )
    assert v2_alive is None  # 最老的非重要行被删


async def test_retention_noop_under_limit(client, db):
    """补充：未超 20 版时保留策略不删任何行。"""
    template = _create_template(client)
    for count in range(5, 10):
        client.put(
            f"/api/templates/{template['id']}", json={"estimated_subtask_count": count}
        )

    total, min_version, max_version = await _version_stats(db, template["id"])
    assert total == 6  # 创建 1 行 + 更新 5 行
    assert (min_version, max_version) == (1, 6)


async def test_all_important_rows_are_never_deleted(client, db):
    """补充（防御）：全部行都重要时宁可超出上限也不自动删除。"""
    template = _create_template(client)
    await _seed_history(db, template["id"], up_to=20)
    await db.execute(
        update(RuleTemplateVersion)
        .where(RuleTemplateVersion.template_id == uuid.UUID(template["id"]))
        .values(is_important=True)
    )
    await db.commit()

    resp = client.put(
        f"/api/templates/{template['id']}", json={"estimated_subtask_count": 99}
    )
    assert resp.json()["data"]["version"] == 21

    count, min_version, max_version = await _version_stats(db, template["id"])
    assert count == 21  # 无可淘汰行，全量保留
    assert (min_version, max_version) == (1, 21)

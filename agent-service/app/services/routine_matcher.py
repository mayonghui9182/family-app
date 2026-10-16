"""Step 4 模板匹配（轻量关键词实现；Change 5 对比学习后升级为 diff 驱动）。"""
from typing import Any


class RoutineMatcher:
    """在家庭启用模板中做包含式标题匹配，命中返回模板行，未命中返回 None。"""

    @staticmethod
    def match(message: str, templates: list[dict[str, Any]]) -> dict[str, Any] | None:
        if not message:
            return None
        for tpl in templates:
            name = str(tpl.get("name") or tpl.get("title") or "")
            if name and name in message:
                return tpl
        return None

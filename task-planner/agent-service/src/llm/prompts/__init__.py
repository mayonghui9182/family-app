"""prompt 模板加载器（tasks.md 7.3）。

四份模板与本模块同目录存放（随包分发，容器内同样可用）：
- `classification.j2`：分类（只用既有分类，无匹配归「未分类」）
- `template_match.j2`：模板匹配（近似匹配标注调整，无匹配走冷启动）
- `decomposition.j2`：拆解（2-8 条、优先历史模式、高负载提醒）
- `summary.j2`：每日总结（≥2 次重复才更新、必带 reason）
"""

from __future__ import annotations

from pathlib import Path
from typing import Any

from jinja2 import Environment, FileSystemLoader, StrictUndefined, select_autoescape

_PROMPTS_DIR = Path(__file__).resolve().parent

# 模板内容全部为 prompt 文本，无 HTML，关闭自动转义；
# StrictUndefined 保证缺变量时立即报错而不是静默渲染空串
_env = Environment(
    loader=FileSystemLoader(str(_PROMPTS_DIR)),
    autoescape=select_autoescape([]),
    undefined=StrictUndefined,
    keep_trailing_newline=False,
    trim_blocks=True,
    lstrip_blocks=True,
)

# 模板文件名白名单：防止外部传入任意文件名读到无关文件
_TEMPLATE_FILES = {
    "classification": "classification.j2",
    "template_match": "template_match.j2",
    "decomposition": "decomposition.j2",
    "summary": "summary.j2",
}


def render_prompt(name: str, **context: Any) -> str:
    """按名称渲染 prompt 模板；未知名称抛 ValueError。"""
    filename = _TEMPLATE_FILES.get(name)
    if filename is None:
        raise ValueError(f"未知的 prompt 模板：{name}")
    return _env.get_template(filename).render(**context)

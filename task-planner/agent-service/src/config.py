"""agent-service 配置加载：LLM、MCP 端点、时区与日志级别。

约定：
- 字段名与环境变量同名（大小写不敏感）
- `LLM_API_KEY` 无默认值，缺失时由 LLM 适配层在调用处报错，不在启动期崩溃
- `LLM_BASE_URL` 可指向本地桩服务，测试与离线开发用
"""

from __future__ import annotations

from functools import lru_cache

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    """agent-service 运行参数。"""

    backend_base_url: str = "http://backend:8080"
    internal_api_secret: str = ""

    model_config = SettingsConfigDict(env_file=".env", env_file_encoding="utf-8", extra="ignore")

    # ---------- LLM ----------
    llm_api_key: str = ""
    llm_base_url: str = "https://api.deepseek.com/v1"
    llm_model: str = "deepseek-chat"
    # 单次 LLM 调用超时（秒）与拆解链路总超时（秒）
    llm_timeout_seconds: float = 30.0
    decomposition_total_timeout_seconds: float = 30.0
    summary_llm_timeout_seconds: float = 60.0

    # ---------- MCP ----------
    mcp_task_service_url: str = "http://task-service:8100/mcp"
    mcp_timeout_seconds: float = 10.0

    # ---------- 对外地址 ----------
    agent_service_url: str = "http://agent-service:8101"

    # ---------- 运行 ----------
    tz: str = "Asia/Shanghai"
    log_level: str = "INFO"
    # 每日总结触发时刻（server-local，24 小时制）
    summary_hour: int = 22
    summary_minute: int = 0


@lru_cache
def get_settings() -> Settings:
    """配置单例：进程内只解析一次环境变量。"""
    return Settings()


settings = get_settings()

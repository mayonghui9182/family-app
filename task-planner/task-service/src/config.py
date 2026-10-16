"""task-service 配置加载：环境变量 → 强类型 settings 单例。

约定：
- 字段名与环境变量同名（大小写不敏感），如 `POSTGRES_HOST` → `postgres_host`
- 测试可通过 `DATABASE_URL` 显式覆盖，指向独立的 task_db_test
- 不在代码中出现任何默认密钥
"""

from __future__ import annotations

from functools import lru_cache

from pydantic import Field
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    """task-service 运行参数。"""

    model_config = SettingsConfigDict(env_file=".env", env_file_encoding="utf-8", extra="ignore")

    # ---------- 数据库 ----------
    postgres_host: str = "127.0.0.1"
    postgres_port: int = 5432
    postgres_user: str = "tp"
    postgres_password: str = "tp"
    postgres_db: str = "task_db"
    # 显式连接串优先于 POSTGRES_* 组装结果（测试与本地开发用）
    database_url_override: str | None = Field(default=None, alias="DATABASE_URL")

    # ---------- 运行 ----------
    tz: str = "Asia/Shanghai"
    log_level: str = "INFO"
    backend_base_url: str = "http://backend:8080"
    internal_api_secret: str = ""

    # ---------- MCP 传输安全 ----------
    # DNS rebinding 防护的 Host 白名单（逗号分隔，支持 `host:*` 通配任意端口）。
    # MCP SDK 在 host 为回环默认值时会自动开启该防护，且只放行 localhost 类
    # Host 头；compose 内 agent-service 以服务名访问（Host: task-service:8100），
    # 不显式放行会被拒 421 Misdirected Request。保留防护、只加内网服务名。
    mcp_allowed_hosts: str = "127.0.0.1:*,localhost:*,[::1]:*,task-service,task-service:*"

    @property
    def mcp_allowed_host_list(self) -> list[str]:
        """Host 白名单列表：按逗号切分、去空白、丢空项。"""
        return [item.strip() for item in self.mcp_allowed_hosts.split(",") if item.strip()]

    @property
    def sqlalchemy_url(self) -> str:
        """asyncpg 连接串；环境变量 `DATABASE_URL` 存在时直接使用。"""
        if self.database_url_override:
            return self.database_url_override
        return (
            f"postgresql+asyncpg://{self.postgres_user}:{self.postgres_password}"
            f"@{self.postgres_host}:{self.postgres_port}/{self.postgres_db}"
        )


@lru_cache
def get_settings() -> Settings:
    """配置单例：进程内只解析一次环境变量。"""
    return Settings()


settings = get_settings()

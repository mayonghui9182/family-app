"""在临时独立数据库验证微信会话迁移与保留清理，不读取或清理应用业务库。"""

import json
import os
import re
import subprocess
import uuid
from pathlib import Path


def main() -> None:
    container = "familyapp-postgres"
    inspected = subprocess.check_output(["docker", "inspect", container], text=True)
    config = json.loads(inspected)[0]
    settings = dict(item.split("=", 1) for item in config["Config"]["Env"] if "=" in item)
    username = settings.get("POSTGRES_USER", "postgres")
    password = settings.get("POSTGRES_PASSWORD", "")
    ports = config["NetworkSettings"]["Ports"].get("5432/tcp") or []
    if not ports or not password:
        raise RuntimeError("隔离验证需要已发布 PostgreSQL 端口和已配置密码")
    port = ports[0]["HostPort"]
    database = "familyapp_wechat_it_default_" + uuid.uuid4().hex
    if not re.fullmatch(r"familyapp_wechat_it_default_[a-f0-9]{32}", database):
        raise RuntimeError("拒绝不符合隔离前缀的数据库名称")
    environment = os.environ.copy()
    environment.update({
        "FAMILYAPP_POSTGRES_TEST_URL": f"jdbc:postgresql://127.0.0.1:{port}/{database}",
        "FAMILYAPP_POSTGRES_TEST_USER": username,
        "FAMILYAPP_POSTGRES_TEST_PASSWORD": password,
    })
    subprocess.run(["docker", "exec", container, "createdb", "-U", username, database], check=True)
    try:
        subprocess.run([
            "./gradlew", "test", "--tests",
            "com.familyapp.wechat.WechatConversationPostgresIntegrationTest",
            "--tests", "com.familyapp.wechat.conversation.WechatConversationRetentionPostgresIntegrationTest",
        ], cwd=Path(__file__).resolve().parents[1] / "backend", env=environment, check=True)
    finally:
        # 名称由本次创建固定，且已有严格前缀校验；只删除本次临时库。
        subprocess.run(["docker", "exec", container, "dropdb", "-U", username, database], check=True)


if __name__ == "__main__":
    main()

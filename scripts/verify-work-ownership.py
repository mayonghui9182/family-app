"""独立随机 PostgreSQL 和临时容器验证工作权限；不访问业务数据库。"""

import importlib
import importlib.metadata
import json
import secrets
import shutil
import subprocess
import sys
import tempfile
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
WORK_ROOT = ROOT
if '--candidate' in sys.argv:
    WORK_ROOT = Path(json.loads((ROOT / 'docs/daily-candidate-assembly.json').read_text())['directory'])
    assert WORK_ROOT.resolve().is_relative_to(ROOT / '.volumes/daily-candidates')
PREFIX = "familyapp-ownership-test-" + secrets.token_hex(4)
LABEL = "familyapp.test.run=" + PREFIX


def run(args, *, capture=False):
    """失败不打印含数据库密码的命令参数。"""
    result = subprocess.run(args, text=True, capture_output=capture, check=False)
    if result.returncode:
        raise RuntimeError("测试步骤失败，退出码 " + str(result.returncode))
    return result.stdout if capture else None


def main():
    """只清理本轮标签一致的临时容器和网络。"""
    password = secrets.token_urlsafe(24)
    database = PREFIX.replace("-", "_") + "_test"
    names = []
    deps = tempfile.TemporaryDirectory(prefix="familyapp-ownership-deps-", dir=ROOT / "tmp_probe")
    Path(deps.name).chmod(0o755)
    # 复制已有纯 Python 测试依赖，容器不需要连接包镜像站。
    for module_name in ("pytest", "_pytest", "pytest_asyncio", "pluggy", "iniconfig", "packaging", "pygments", "py"):
        module = importlib.import_module(module_name)
        source = Path(module.__file__)
        if source.name == "__init__.py":
            shutil.copytree(source.parent, Path(deps.name) / module_name)
        else:
            shutil.copy2(source, Path(deps.name) / (module_name + ".py"))
    for distribution_name in ("pytest", "pytest-asyncio", "pluggy", "iniconfig", "packaging", "pygments"):
        distribution = importlib.metadata.distribution(distribution_name)
        metadata_dir = Path(deps.name) / (distribution_name.replace("-", "_") + "-" + distribution.version + ".dist-info")
        metadata_dir.mkdir()
        metadata = f"Metadata-Version: 2.1\nName: {distribution_name}\nVersion: {distribution.version}\n"
        (metadata_dir / "METADATA").write_text(metadata, encoding="utf-8")
    try:
        run(["docker", "run", "--rm", "--network", "none", "--entrypoint", "python",
             "-v", deps.name + ":/test-deps:ro", "-e", "PYTHONPATH=/test-deps",
             "family-app-tp-task-service:latest", "-c", "import pytest, pytest_asyncio"])
        run(["docker", "network", "create", "--internal", "--label", LABEL, PREFIX], capture=True)
        pg = PREFIX + "-pg"
        names.append(pg)
        run(["docker", "run", "-d", "--name", pg, "--label", LABEL,
             "--network", PREFIX, "-e", "POSTGRES_USER=tester", "-e", "POSTGRES_PASSWORD=" + password,
             "-e", "POSTGRES_DB=" + database, "postgres:16-alpine"], capture=True)
        for _ in range(30):
            ready = subprocess.run(["docker", "exec", pg, "pg_isready", "-U", "tester"],
                                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=False)
            if ready.returncode == 0:
                break
            time.sleep(1)
        else:
            raise RuntimeError("测试 PostgreSQL 启动失败")
        tester = PREFIX + "-runner"
        names.append(tester)
        url = f"postgresql+asyncpg://tester:{password}@{pg}:5432/{database}"
        command = (
            "alembic upgrade head && alembic downgrade 0002 && alembic upgrade head && "
            "PYTHONPATH=/workspace:/test-deps python -m pytest tests -q -p pytest_asyncio.plugin -p no:cacheprovider"
        )
        run(["docker", "run", "--rm", "--name", tester, "--label", LABEL,
             "--network", PREFIX, "--entrypoint", "sh", "-w", "/workspace",
             "-v", str(WORK_ROOT / "task-planner/task-service") + ":/workspace:ro",
             "-v", deps.name + ":/test-deps:ro", "-e", "PYTEST_DISABLE_PLUGIN_AUTOLOAD=1",
             "-e", "TEST_DATABASE_URL=" + url, "-e", "DATABASE_URL=" + url,
             "family-app-tp-task-service:latest", "-c", command])
        auth = PREFIX + "-auth"
        names.append(auth)
        secret = secrets.token_urlsafe(24)
        run(["docker", "run", "-d", "--name", auth, "--label", LABEL, "--network", PREFIX,
             "-v", str(ROOT / "scripts/ownership-auth-stub.py") + ":/stub.py:ro",
             "-e", "TEST_SECRET=" + secret, "python:3.12-slim", "python", "/stub.py"], capture=True)
        app = PREFIX + "-app"
        names.append(app)
        run(["docker", "run", "-d", "--name", app, "--label", LABEL,
             "--network", PREFIX, "--entrypoint", "python", "-w", "/workspace",
             "-v", str(WORK_ROOT / "task-planner/task-service") + ":/workspace:ro",
             "-e", "DATABASE_URL=" + url, "-e", "BACKEND_BASE_URL=http://" + auth + ":8080",
             "-e", "MCP_ALLOWED_HOSTS=" + app + ":8100,localhost:*,127.0.0.1:*",
             "-e", "INTERNAL_API_SECRET=" + secret,
             "family-app-tp-task-service:latest", "-m", "uvicorn", "src.main:app", "--host", "0.0.0.0", "--port", "8100"], capture=True)
        for _ in range(30):
            ready = subprocess.run(["docker", "exec", app, "python", "-c",
                "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8100/healthz')"],
                stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=False)
            if ready.returncode == 0:
                break
            time.sleep(1)
        else:
            raise RuntimeError("隔离工作服务启动失败")
        agent_runner = PREFIX + "-agent-tests"
        names.append(agent_runner)
        run(["docker", "run", "--rm", "--name", agent_runner, "--label", LABEL,
             "--network", PREFIX, "--entrypoint", "sh", "-w", "/workspace",
             "-v", str(WORK_ROOT / "task-planner/agent-service") + ":/workspace:ro",
             "-v", deps.name + ":/test-deps:ro", "-e", "PYTEST_DISABLE_PLUGIN_AUTOLOAD=1",
             "-e", "MCP_TASK_SERVICE_URL=http://" + app + ":8100/mcp",
             "-e", "BACKEND_BASE_URL=http://" + auth + ":8080",
             "-e", "INTERNAL_API_SECRET=" + secret, "-e", "TP_E2E_ALLOW_WRITES=1",
             "family-app-tp-agent-service:latest", "-c",
             "PYTHONPATH=/workspace:/test-deps python -m pytest tests -q -p pytest_asyncio.plugin -p no:cacheprovider"])
    finally:
        for name in reversed(names):
            check = subprocess.run(["docker", "inspect", "--format",
                '{{index .Config.Labels "familyapp.test.run"}}', name],
                text=True, capture_output=True, check=False)
            if check.returncode == 0 and check.stdout.strip() == PREFIX:
                subprocess.run(["docker", "rm", "-f", "-v", name], stdout=subprocess.DEVNULL, check=False)
        subprocess.run(["docker", "network", "rm", PREFIX], stdout=subprocess.DEVNULL,
                       stderr=subprocess.DEVNULL, check=False)
        deps.cleanup()


if __name__ == "__main__":
    main()

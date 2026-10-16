# task-service 镜像：Python 3.12 slim，非 root 运行，启动前先跑 Alembic 迁移
FROM python:3.12-slim

ENV PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    PIP_NO_CACHE_DIR=1 \
    TZ=Asia/Shanghai

WORKDIR /app

# 先按元数据安装运行依赖，源码修改不再触发依赖下载
COPY pyproject.toml ./
RUN python -c "import subprocess,tomllib; from pathlib import Path; subprocess.check_call(['pip','install','setuptools>=68','wheel',*tomllib.loads(Path('pyproject.toml').read_text())['project']['dependencies']])"
COPY src ./src
RUN pip install --no-deps --no-build-isolation .

# 迁移脚本随运行镜像发布
COPY alembic.ini ./
COPY alembic ./alembic
COPY docker-entrypoint.sh ./docker-entrypoint.sh
RUN chmod +x ./docker-entrypoint.sh

RUN useradd --create-home --uid 10001 appuser
USER appuser

EXPOSE 8100

HEALTHCHECK --interval=10s --timeout=5s --retries=12 --start-period=20s \
    CMD python -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8100/healthz', timeout=3)"

ENTRYPOINT ["./docker-entrypoint.sh"]
CMD ["uvicorn", "src.main:app", "--host", "0.0.0.0", "--port", "8100"]

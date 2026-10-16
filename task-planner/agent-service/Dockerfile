# agent-service 镜像：Python 3.12 slim，无状态服务，非 root 运行
FROM python:3.12-slim

ENV PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    PIP_NO_CACHE_DIR=1 \
    TZ=Asia/Shanghai

WORKDIR /app

COPY pyproject.toml ./
RUN python -c "import subprocess,tomllib; from pathlib import Path; subprocess.check_call(['pip','install','setuptools>=68','wheel',*tomllib.loads(Path('pyproject.toml').read_text())['project']['dependencies']])"
COPY src ./src
RUN pip install --no-deps --no-build-isolation .


RUN useradd --create-home --uid 10001 appuser
USER appuser

EXPOSE 8101

HEALTHCHECK --interval=10s --timeout=5s --retries=12 --start-period=15s \
    CMD python -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8101/healthz', timeout=3)"

CMD ["uvicorn", "src.main:app", "--host", "0.0.0.0", "--port", "8101"]

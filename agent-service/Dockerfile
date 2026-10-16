FROM python:3.12-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1

WORKDIR /srv/agent-service
COPY pyproject.toml README_DOCKER.md ./
COPY app ./app
RUN pip install --no-cache-dir .

RUN useradd -m appuser && chown -R appuser /srv/agent-service
USER appuser

EXPOSE 8700
HEALTHCHECK --interval=30s --timeout=3s --retries=3 \
    CMD python -c "import urllib.request,sys;sys.exit(0 if urllib.request.urlopen('http://127.0.0.1:8700/healthz',timeout=2).status==200 else 1)"
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8700"]

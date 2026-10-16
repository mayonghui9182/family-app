FROM python:3.12-slim
WORKDIR /app
RUN useradd -m -u 1000 appuser
COPY --chown=appuser:appuser pyproject.toml ./
RUN pip install --no-cache-dir . && pip install --no-cache-dir uvicorn[standard]
COPY --chown=appuser:appuser app ./app
USER appuser
EXPOSE 8800
HEALTHCHECK --interval=10s --timeout=3s --retries=5 \
    CMD python -c "import urllib.request,sys;r=urllib.request.urlopen('http://localhost:8800/healthz',timeout=2);sys.exit(0 if r.status==200 else 1)"
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8800"]

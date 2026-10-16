FROM python:3.12-slim
WORKDIR /app
COPY requirements.txt ./
RUN pip install --no-cache-dir -r requirements.txt
COPY daily_mcp.py remote_mcp.py ./
RUN useradd --uid 10001 --no-create-home --shell /usr/sbin/nologin daily
USER daily
CMD ["python", "remote_mcp.py"]

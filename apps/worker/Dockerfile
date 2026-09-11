FROM python:3.12-slim
COPY --from=ghcr.io/astral-sh/uv:0.11.32 /uv /usr/local/bin/uv
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
ENV PATH="/opt/studyos/apps/worker/.venv/bin:$PATH"
WORKDIR /opt/studyos
COPY packages/python-ai-core packages/python-ai-core
COPY apps/worker apps/worker
RUN uv sync --project apps/worker --locked --no-dev --no-editable && useradd --create-home --uid 10001 studyos
USER studyos
EXPOSE 8001
CMD ["python", "-m", "worker.main"]

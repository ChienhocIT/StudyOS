FROM python:3.12-slim
COPY --from=ghcr.io/astral-sh/uv:0.11.32 /uv /usr/local/bin/uv
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
ENV PATH="/opt/studyos/apps/ai-service/.venv/bin:$PATH"
WORKDIR /opt/studyos
COPY packages/python-ai-core packages/python-ai-core
COPY apps/ai-service apps/ai-service
RUN uv sync --project apps/ai-service --locked --no-dev --no-editable && useradd --create-home --uid 10001 studyos
USER studyos
EXPOSE 8000
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000", "--no-access-log"]

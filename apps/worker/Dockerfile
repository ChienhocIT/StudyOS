FROM python:3.12-slim
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
WORKDIR /opt/studyos
COPY packages/python-ai-core packages/python-ai-core
COPY apps/worker apps/worker
RUN pip install --no-cache-dir ./packages/python-ai-core ./apps/worker && useradd --create-home --uid 10001 studyos
USER studyos
EXPOSE 8001
CMD ["python", "-m", "worker.main"]

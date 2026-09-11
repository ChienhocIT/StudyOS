import asyncio
import contextlib
import logging
import signal
import sys
from functools import partial

import aio_pika
from prometheus_client import start_http_server
from psycopg_pool import AsyncConnectionPool
from studyos_ai.config import Settings
from studyos_ai.providers import build_provider

from .messaging import QUEUES, Consumer, declare_topology, publish_outbox
from .pipeline import Pipeline


async def run():
    settings = Settings()
    logging.basicConfig(level=logging.INFO, format="%(message)s")
    enabled = {q.strip() for q in settings.worker_queues.split(",") if q.strip()}
    if not enabled or enabled - {q.name for q in QUEUES}:
        raise ValueError("WORKER_QUEUES includes unknown queues or is empty")
    provider = build_provider(settings)
    pool = AsyncConnectionPool(
        settings.database_derived_data_url.get_secret_value(),
        min_size=1,
        max_size=settings.worker_concurrency + 3,
        open=False,
        kwargs={"autocommit": True},
    )
    await pool.open(wait=True, timeout=30)
    connection = await aio_pika.connect_robust(
        settings.rabbitmq_url.get_secret_value(), timeout=15
    )
    stop = asyncio.Event()
    loop = asyncio.get_running_loop()
    for sig in (signal.SIGTERM, signal.SIGINT):
        with contextlib.suppress(NotImplementedError):
            loop.add_signal_handler(sig, stop.set)
    metrics_server, _ = start_http_server(settings.worker_metrics_port)
    async with connection:
        channel = await connection.channel(
            publisher_confirms=True, on_return_raises=True
        )
        await channel.set_qos(prefetch_count=settings.worker_concurrency)
        _, queues = await declare_topology(channel)
        consumer = Consumer(
            Pipeline(settings, pool, provider),
            channel,
            asyncio.Semaphore(settings.worker_concurrency),
        )
        tags = []
        for spec in QUEUES:
            if spec.name in enabled:
                tags.append(
                    (
                        queues[spec.name],
                        await queues[spec.name].consume(
                            partial(consumer.process, spec), no_ack=False
                        ),
                    )
                )
        publisher = asyncio.create_task(publish_outbox(pool, channel, stop))
        logging.getLogger("studyos.worker").info(
            '{"service":"studyos-worker","status":"ready"}'
        )
        try:
            await stop.wait()
        finally:
            for queue, tag in tags:
                await queue.cancel(tag)
            stop.set()
            publisher.cancel()
            with contextlib.suppress(asyncio.CancelledError):
                await publisher
            await channel.close()
            metrics_server.shutdown()
            await pool.close()
            if hasattr(provider, "close"):
                await provider.close()


if __name__ == "__main__":
    if sys.platform == "win32":
        asyncio.set_event_loop_policy(asyncio.WindowsSelectorEventLoopPolicy())
    asyncio.run(run())

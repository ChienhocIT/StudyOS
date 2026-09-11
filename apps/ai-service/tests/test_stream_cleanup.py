import asyncio

import pytest
from app.main import until_disconnected


async def test_disconnect_cancels_blocked_upstream_and_runs_cleanup():
    closed = asyncio.Event()

    async def upstream():
        try:
            await asyncio.Event().wait()
            yield "never"
        finally:
            closed.set()

    class DisconnectedRequest:
        async def is_disconnected(self):
            return True

    stream = until_disconnected(upstream(), DisconnectedRequest())
    with pytest.raises(StopAsyncIteration):
        await asyncio.wait_for(anext(stream), timeout=2)
    assert closed.is_set()


async def test_cancelling_consumer_closes_blocked_upstream():
    entered, closed = asyncio.Event(), asyncio.Event()

    async def upstream():
        try:
            entered.set()
            await asyncio.Event().wait()
            yield "never"
        finally:
            closed.set()

    class ConnectedRequest:
        async def is_disconnected(self):
            return False

    stream = until_disconnected(upstream(), ConnectedRequest())
    pending = asyncio.create_task(anext(stream))
    await asyncio.wait_for(entered.wait(), timeout=2)
    pending.cancel()
    with pytest.raises(asyncio.CancelledError):
        await pending
    assert closed.is_set()

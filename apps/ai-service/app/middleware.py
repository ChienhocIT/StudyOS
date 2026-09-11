import asyncio

from starlette.responses import JSONResponse


class BoundedBodyMiddleware:
    """Bound actual HTTP bytes (including chunked transfers) before JSON parsing."""
    def __init__(self, app, max_bytes: int = 300000):
        self.app, self.max_bytes = app, max_bytes

    async def __call__(self, scope, receive, send):
        if scope["type"] != "http" or scope.get("method") != "POST":
            return await self.app(scope, receive, send)
        headers = dict(scope.get("headers", []))
        length = headers.get(b"content-length", b"")
        if length and (not length.isdigit() or int(length) > self.max_bytes):
            return await JSONResponse({"code": "REQUEST_TOO_LARGE"}, status_code=413)(scope, receive, send)
        body = bytearray()
        try:
            async with asyncio.timeout(15):
                while True:
                    message = await receive()
                    if message["type"] == "http.disconnect":
                        return
                    body.extend(message.get("body", b""))
                    if len(body) > self.max_bytes:
                        return await JSONResponse({"code": "REQUEST_TOO_LARGE"}, status_code=413)(scope, receive, send)
                    if not message.get("more_body", False):
                        break
        except TimeoutError:
            return await JSONResponse({"code": "REQUEST_BODY_TIMEOUT"}, status_code=408)(scope, receive, send)
        replayed = False

        async def replay():
            nonlocal replayed
            if not replayed:
                replayed = True
                return {"type": "http.request", "body": bytes(body), "more_body": False}
            return await receive()

        await self.app(scope, replay, send)

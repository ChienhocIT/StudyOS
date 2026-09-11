import asyncio
import sys

import uvicorn

server = uvicorn.Server(
    uvicorn.Config("app.main:app", host="0.0.0.0", port=8000, access_log=False)
)
if sys.platform == "win32":
    # Modern Uvicorn installs its own loop factory; policy-only configuration is ignored.
    asyncio.run(server.serve(), loop_factory=asyncio.SelectorEventLoop)
else:
    asyncio.run(server.serve())

"""Pinned-address HTTP fetch. DNS is checked once and the validated address is used by the socket."""

import http.client
import ipaddress
import socket
import ssl
import time
from urllib.parse import urljoin, urlsplit

from .errors import PipelineError


def validate_url(
    url: str, resolver=socket.getaddrinfo
) -> tuple[str, str, int, str, str]:
    try:
        parsed = urlsplit(url)
        if (
            parsed.scheme not in {"http", "https"}
            or not parsed.hostname
            or parsed.username
            or parsed.password
            or parsed.fragment
        ):
            raise ValueError("Invalid URL")
        if any(ord(c) < 32 for c in url) or "\\" in url:
            raise ValueError("Invalid URL characters")
        port = parsed.port or (443 if parsed.scheme == "https" else 80)
        if port != (443 if parsed.scheme == "https" else 80):
            raise ValueError("Nonstandard port")
        host = parsed.hostname.encode("idna").decode("ascii")
        if host.lower() in {
            "localhost",
            "metadata.google.internal",
        } or host.lower().endswith((".localhost", ".local", ".internal")):
            raise ValueError("Private hostname")
        addresses = resolver(host, port, type=socket.SOCK_STREAM)
        if not addresses:
            raise ValueError("DNS has no addresses")
        ips = [ipaddress.ip_address(item[4][0].split("%")[0]) for item in addresses]
        for ip in ips:
            # Also reject transition encodings that can tunnel to forbidden addresses.
            if not ip.is_global or (
                isinstance(ip, ipaddress.IPv6Address)
                and (ip.ipv4_mapped or ip.sixtofour or ip.teredo)
            ):
                raise ValueError("Private or special-purpose address")
        return (
            parsed.scheme,
            host,
            port,
            str(ips[0]),
            (parsed.path or "/") + ("?" + parsed.query if parsed.query else ""),
        )
    except socket.gaierror as exc:
        raise PipelineError(
            "SOURCE_FETCH_UNAVAILABLE", "Source hostname could not be resolved.", True
        ) from exc
    except (ValueError, UnicodeError) as exc:
        raise PipelineError(
            "SOURCE_URL_BLOCKED",
            "URL must point to a public HTTP or HTTPS address on a standard port.",
        ) from exc


def fetch_public_url(url: str, max_bytes: int = 5242880, timeout: float = 15) -> bytes:
    deadline = time.monotonic() + timeout
    for _ in range(4):
        scheme, host, port, address, path = validate_url(url)
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            raise PipelineError(
                "SOURCE_FETCH_TIMEOUT", "Source download timed out.", True
            )
        connection = http.client.HTTPConnection(host, port, timeout=remaining)
        try:
            # Connect to validated literal address: a second DNS resolution cannot rebind.
            raw_socket = socket.create_connection((address, port), timeout=remaining)
            try:
                connection.sock = (
                    ssl.create_default_context().wrap_socket(
                        raw_socket, server_hostname=host
                    )
                    if scheme == "https"
                    else raw_socket
                )
            except BaseException:
                raw_socket.close()
                raise
            connection.request(
                "GET",
                path,
                headers={
                    "Host": host,
                    "User-Agent": "StudyOS-SourceReader/1.0",
                    "Accept": "text/html,text/plain",
                    "Accept-Encoding": "identity",
                },
            )
            response = connection.getresponse()
            if response.status in {301, 302, 303, 307, 308}:
                location = response.getheader("Location")
                if not location:
                    raise PipelineError(
                        "SOURCE_FETCH_INVALID", "Source returned an invalid redirect."
                    )
                url = urljoin(url, location)
                continue
            if response.status == 429 or response.status >= 500:
                raise PipelineError(
                    "SOURCE_FETCH_UNAVAILABLE",
                    "Source server is temporarily unavailable.",
                    True,
                )
            if response.status != 200:
                raise PipelineError(
                    "SOURCE_FETCH_DENIED", "Source server denied access to this page."
                )
            media = (
                (response.getheader("Content-Type") or "").split(";")[0].strip().lower()
            )
            if media not in {"text/html", "text/plain", "application/xhtml+xml"}:
                raise PipelineError(
                    "SOURCE_UNSUPPORTED_TYPE",
                    "URL must return an HTML or plain-text page.",
                )
            if response.getheader("Content-Encoding", "identity") not in {
                "identity",
                "",
            }:
                raise PipelineError(
                    "SOURCE_FETCH_ENCODING",
                    "Compressed responses are not accepted by the bounded source fetcher.",
                )
            declared = response.getheader("Content-Length")
            if declared and (not declared.isdigit() or int(declared) > max_bytes):
                raise PipelineError(
                    "SOURCE_FILE_TOO_LARGE", "Source page exceeds the download limit."
                )
            parts, size = [], 0
            while True:
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    raise PipelineError(
                        "SOURCE_FETCH_TIMEOUT", "Source download timed out.", True
                    )
                if connection.sock:
                    connection.sock.settimeout(remaining)
                part = response.read1(min(65536, max_bytes + 1 - size))
                if not part:
                    break
                parts.append(part)
                size += len(part)
                if size > max_bytes:
                    raise PipelineError(
                        "SOURCE_FILE_TOO_LARGE",
                        "Source page exceeds the download limit.",
                    )
            return b"".join(parts)
        except (OSError, http.client.HTTPException) as exc:
            raise PipelineError(
                "SOURCE_FETCH_UNAVAILABLE", "Source page could not be downloaded.", True
            ) from exc
        finally:
            connection.close()
    raise PipelineError(
        "SOURCE_REDIRECT_LIMIT", "Source page redirected too many times."
    )

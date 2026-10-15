"""Bridge a stdio MCP client to PSD2Live's Streamable HTTP endpoint.

Use PSD2Live's HTTP endpoint directly whenever the host supports Streamable
HTTP. This dependency-free bridge is a compatibility fallback for stdio-only
hosts. It keeps stdout reserved for line-delimited JSON-RPC messages.
"""

from __future__ import annotations

import base64
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

try:
    import winreg
except ImportError:  # pragma: no cover - only available on Windows
    winreg = None


DEFAULT_PORT = 23871
REQUEST_TIMEOUT_SECONDS = float(os.environ.get("PSD2LIVE_MCP_TIMEOUT", "60"))
TOKEN_ENV = "PSD2LIVE_MCP_TOKEN"
TRANSIENT_HTTP_STATUSES = {408, 425, 429, 502, 503, 504}
SAFE_PROTOCOL_METHODS = {
    "initialize",
    "ping",
    "tools/list",
    "resources/list",
    "resources/read",
    "resources/templates/list",
    "notifications/initialized",
    "notifications/cancelled",
}
# Read-only tools, used until the server's tools/list arrives and replaces this set
# with every tool it marks read-only or idempotent.
SAFE_PSD2LIVE_TOOLS = {
    "workspace_inspect",
    "workspace_list_operations",
    "workspace_get_operation",
    "history_list",
    "job_get",
    "job_list",
}


def _decode_java_preferences_value(value: str) -> str:
    """Decode the slash escaping used by Java Preferences on Windows."""
    result: list[str] = []
    index = 0
    while index < len(value):
        if value[index] == "/" and index + 1 < len(value):
            index += 1
            result.append("/" if value[index] == "/" else value[index].upper())
        else:
            result.append(value[index])
        index += 1
    return "".join(result)


def _read_saved(name: str) -> str | None:
    """Read a value PSD2Live saved in its Java preferences node (Windows only)."""
    if winreg is None:
        return None
    try:
        with winreg.OpenKey(
            winreg.HKEY_CURRENT_USER,
            r"Software\JavaSoft\Prefs\io\github\psd2live\agent",
        ) as key:
            value, _ = winreg.QueryValueEx(key, name)
        return _decode_java_preferences_value(value)
    except OSError:
        return None


def get_token() -> str | None:
    return os.environ.get(TOKEN_ENV) or _read_saved("agent_mcp_bearer_token")


def require_local_endpoint(endpoint: str) -> str:
    """The proxy sends the bearer token. It only talks to this machine."""
    parsed = urllib.parse.urlparse(endpoint)
    host = (parsed.hostname or "").lower()
    if parsed.scheme != "http" or host not in {"127.0.0.1", "localhost", "::1"}:
        raise SystemExit("PSD2LIVE_MCP_ENDPOINT must be an http URL on 127.0.0.1, localhost, or ::1")
    return endpoint


def get_endpoint() -> str:
    endpoint = os.environ.get("PSD2LIVE_MCP_ENDPOINT")
    if endpoint:
        return require_local_endpoint(endpoint)
    port = _read_saved("agent_mcp_port")
    return f"http://127.0.0.1:{port if port and port.isdigit() else DEFAULT_PORT}/mcp"


ENDPOINT = get_endpoint()


def _is_retry_safe(message: object) -> bool:
    if not isinstance(message, dict):
        return False
    method = message.get("method")
    if method in SAFE_PROTOCOL_METHODS:
        return True
    if method != "tools/call":
        return False
    params = message.get("params")
    return isinstance(params, dict) and params.get("name") in SAFE_PSD2LIVE_TOOLS


def _build_headers(
    token: str,
    session_id: str | None,
    protocol_version: str | None,
) -> dict[str, str]:
    headers = {
        "Accept": "application/json, text/event-stream",
        "Authorization": f"Bearer {token}",
        "Content-Type": "application/json",
    }
    if session_id:
        headers["Mcp-Session-Id"] = session_id
    if protocol_version:
        headers["MCP-Protocol-Version"] = protocol_version
    return headers


def _write_message(message: str) -> None:
    message = message.strip()
    if message:
        sys.stdout.write(message + "\n")
        sys.stdout.flush()


def _write_json_payload(payload: str) -> None:
    payload = payload.strip()
    if not payload:
        return
    try:
        parsed = json.loads(payload)
    except json.JSONDecodeError as error:
        sys.stderr.write(f"PSD2Live MCP returned invalid JSON: {error}\n")
        return
    _learn_safe_tools(parsed)
    _write_message(json.dumps(parsed, separators=(",", ":"), ensure_ascii=False))


def _learn_safe_tools(message: object) -> None:
    """Writes repeat their request ID, so the server marks them idempotent and a retry recovers the result."""
    result = message.get("result") if isinstance(message, dict) else None
    tools = result.get("tools") if isinstance(result, dict) else None
    if not isinstance(tools, list):
        return
    for tool in tools:
        if not isinstance(tool, dict) or not isinstance(tool.get("name"), str):
            continue
        hints = tool.get("annotations")
        if isinstance(hints, dict) and (hints.get("readOnlyHint") or hints.get("idempotentHint")):
            SAFE_PSD2LIVE_TOOLS.add(tool["name"])
        else:
            SAFE_PSD2LIVE_TOOLS.discard(tool["name"])


def _write_http_body(body: str, content_type: str) -> None:
    if "text/event-stream" not in content_type.lower():
        _write_json_payload(body)
        return

    # Streamable HTTP may return one or more SSE events. A stdio client expects
    # each JSON-RPC message as exactly one line without SSE framing.
    event_data: list[str] = []
    for line in body.splitlines() + [""]:
        if line.startswith("data:"):
            event_data.append(line[5:].lstrip())
        elif not line and event_data:
            _write_json_payload("\n".join(event_data))
            event_data.clear()


def _write_rpc_error(request_id: object, message: str) -> None:
    if request_id is None:
        return
    _write_message(
        json.dumps(
            {
                "jsonrpc": "2.0",
                "id": request_id,
                "error": {"code": -32000, "message": message},
            },
            separators=(",", ":"),
            ensure_ascii=False,
        )
    )


def _retry_delay(error: urllib.error.HTTPError | None = None) -> float:
    if error is not None:
        retry_after = error.headers.get("Retry-After")
        if retry_after:
            try:
                return min(max(float(retry_after), 0.0), 2.0)
            except ValueError:
                pass
    return 0.2


def _close_session(
    session_id: str | None,
    token: str,
    protocol_version: str | None,
) -> None:
    if not session_id:
        return
    request = urllib.request.Request(
        ENDPOINT,
        headers=_build_headers(token, session_id, protocol_version),
        method="DELETE",
    )
    try:
        with urllib.request.urlopen(request, timeout=min(REQUEST_TIMEOUT_SECONDS, 2.0)):
            pass
    except (urllib.error.HTTPError, urllib.error.URLError, TimeoutError, OSError):
        # Session cleanup is best effort during process shutdown.
        pass


def main() -> int:
    token = get_token()
    if not token:
        sys.stderr.write(
            "PSD2Live MCP token was not found. Start PSD2Live once or set "
            f"{TOKEN_ENV} for this process.\n"
        )
        return 2

    session_id: str | None = None
    protocol_version: str | None = None

    try:
        for raw_line in sys.stdin:
            line = raw_line.strip()
            if not line:
                continue

            try:
                message = json.loads(line)
            except json.JSONDecodeError as error:
                sys.stderr.write(f"Invalid JSON-RPC input: {error}\n")
                continue

            request_id = message.get("id") if isinstance(message, dict) else None
            method = message.get("method") if isinstance(message, dict) else None
            if method == "initialize":
                # A fresh initialize starts a fresh HTTP session. Reusing a stale
                # session header causes otherwise valid reconnects to fail.
                session_id = None
                params = message.get("params") or {}
                if isinstance(params, dict):
                    value = params.get("protocolVersion")
                    if isinstance(value, str):
                        protocol_version = value

            retry_safe = _is_retry_safe(message)
            transient_retried = False
            auth_refreshed = False

            while True:
                request = urllib.request.Request(
                    ENDPOINT,
                    data=line.encode("utf-8"),
                    headers=_build_headers(token, session_id, protocol_version),
                    method="POST",
                )
                try:
                    with urllib.request.urlopen(
                        request,
                        timeout=REQUEST_TIMEOUT_SECONDS,
                    ) as response:
                        session_id = response.headers.get("Mcp-Session-Id", session_id)
                        body = response.read().decode("utf-8")
                        _write_http_body(body, response.headers.get("Content-Type", ""))
                    break
                except urllib.error.HTTPError as error:
                    detail = error.read().decode("utf-8", errors="replace").strip()

                    # Authentication failed before a tool can run. Refresh once,
                    # and retry only when the credential actually changed.
                    if error.code in {401, 403} and not auth_refreshed:
                        refreshed_token = get_token()
                        auth_refreshed = True
                        if refreshed_token and refreshed_token != token:
                            token = refreshed_token
                            continue

                    if error.code == 404 and session_id and method != "initialize":
                        session_id = None
                        summary = (
                            "PSD2Live MCP session expired. Initialize a new MCP session, "
                            "then call workspace_inspect before resuming."
                        )
                    elif (
                        error.code in TRANSIENT_HTTP_STATUSES
                        and retry_safe
                        and not transient_retried
                    ):
                        transient_retried = True
                        time.sleep(_retry_delay(error))
                        continue
                    else:
                        summary = f"PSD2Live MCP returned HTTP {error.code}"
                        if detail:
                            summary += f": {detail}"
                        if not retry_safe and error.code in TRANSIENT_HTTP_STATUSES:
                            summary += (
                                ". The write commit state may be unknown; reconnect and check "
                                "workspace_inspect/history_list before retrying"
                            )

                    sys.stderr.write(summary + "\n")
                    _write_rpc_error(request_id, summary)
                    if method == "initialize":
                        return 1
                    break
                except (urllib.error.URLError, TimeoutError, OSError) as error:
                    if retry_safe and not transient_retried:
                        transient_retried = True
                        time.sleep(_retry_delay())
                        continue

                    summary = f"Cannot reach PSD2Live MCP at {ENDPOINT}: {error}"
                    if not retry_safe:
                        summary += (
                            ". The write commit state is unknown; reconnect and check "
                            "workspace_inspect/history_list before retrying"
                        )
                    sys.stderr.write(summary + "\n")
                    _write_rpc_error(request_id, summary)
                    if method == "initialize":
                        return 1
                    break
    finally:
        _close_session(session_id, token, protocol_version)

    return 0


class McpError(RuntimeError):
    """An operation that answered ok false, or a JSON-RPC or HTTP failure."""

    def __init__(self, message: str, detail: object = None):
        super().__init__(message)
        self.detail = detail


class Client:
    """Call PSD2Live operations from a script, over one Streamable HTTP session.

    Works with both tool profiles: an operation listed as its own tool is called directly, any other through
    workspace_call. Background operations are waited for. Images an operation returns come back as PNG bytes.

        with Client() as p2l:
            state = p2l.state()
            p2l.edit("material_crop", id="ref", canvas_rect={...}, new_id="head")
            data, images = p2l.call("view_render_model", {"scope": "canvas"})
    """

    def __init__(self, endpoint: str | None = None, token: str | None = None, timeout: float = REQUEST_TIMEOUT_SECONDS):
        self.endpoint = require_local_endpoint(endpoint) if endpoint else ENDPOINT
        self.token = token or get_token()
        if not self.token:
            raise McpError(f"PSD2Live MCP token was not found. Start PSD2Live once or set {TOKEN_ENV}.")
        self.timeout = timeout
        self.session_id: str | None = None
        self.protocol_version = "2025-03-26"
        self._ids = 0
        self._tools: dict[str, dict] | None = None

    def __enter__(self) -> "Client":
        return self

    def __exit__(self, *_: object) -> None:
        self.close()

    def close(self) -> None:
        _close_session(self.session_id, self.token, self.protocol_version)
        self.session_id = None

    def _post(self, message: dict) -> dict | None:
        request = urllib.request.Request(
            self.endpoint,
            data=json.dumps(message).encode("utf-8"),
            headers=_build_headers(self.token, self.session_id, self.protocol_version),
            method="POST",
        )
        try:
            with urllib.request.urlopen(request, timeout=self.timeout) as response:
                self.session_id = response.headers.get("Mcp-Session-Id", self.session_id)
                body = response.read().decode("utf-8")
                content_type = response.headers.get("Content-Type", "")
        except urllib.error.HTTPError as error:
            raise McpError(f"PSD2Live MCP returned HTTP {error.code}: {error.read().decode('utf-8', 'replace').strip()}") from error
        except (urllib.error.URLError, TimeoutError, OSError) as error:
            raise McpError(f"Cannot reach PSD2Live MCP at {self.endpoint}: {error}") from error
        if not body.strip():
            return None
        if "text/event-stream" not in content_type.lower():
            return json.loads(body)
        reply = None
        for line in body.splitlines():
            if line.startswith("data:") and line[5:].strip():
                parsed = json.loads(line[5:].strip())
                if isinstance(parsed, dict) and "id" in parsed:
                    reply = parsed
        return reply

    def _rpc(self, method: str, params: dict) -> dict:
        if self.session_id is None and method != "initialize":
            self._initialize()
        self._ids += 1
        reply = self._post({"jsonrpc": "2.0", "id": self._ids, "method": method, "params": params})
        if reply is None:
            raise McpError(f"No reply to {method}")
        if "error" in reply:
            raise McpError(f"{method} failed: {reply['error'].get('message')}", reply["error"])
        return reply["result"]

    def _initialize(self) -> None:
        self._rpc("initialize", {"protocolVersion": self.protocol_version, "capabilities": {},
                                 "clientInfo": {"name": "psd2live-script", "version": "1"}})
        self._post({"jsonrpc": "2.0", "method": "notifications/initialized"})

    def tools(self) -> set[str]:
        return set(self._schemas())

    def _schemas(self) -> dict[str, dict]:
        """Each tool's request properties, by tool name."""
        if self._tools is None:
            self._tools = {tool["name"]: tool.get("inputSchema", {}).get("properties", {}).get("request", {}).get("properties", {})
                           for tool in self._rpc("tools/list", {}).get("tools", [])}
        return self._tools

    def _tool(self, name: str, arguments: dict) -> tuple[dict, list[bytes]]:
        result = self._rpc("tools/call", {"name": name, "arguments": arguments})
        images = [base64.b64decode(part["data"]) for part in result.get("content", []) if part.get("type") == "image"]
        data = result.get("structuredContent")
        if data is None:
            texts = [part["text"] for part in result.get("content", []) if part.get("type") == "text"]
            try:
                data = json.loads(texts[0]) if texts else {}
            except json.JSONDecodeError:
                data = {"ok": not result.get("isError"), "data": {"text": "\n".join(texts)}}
        if result.get("isError") or not data.get("ok", True):
            error = data.get("error", data)
            raise McpError(f"{name}: {error.get('message', error) if isinstance(error, dict) else error}", error)
        return data, images

    def call(self, operation: str, request: dict | None = None, wait_ms: int | None = None) -> tuple[dict, list[bytes]]:
        """Run one operation; returns its data (a finished job's result for background operations) and images.

        wait_ms is for background operations only; they are waited for until they end either way."""
        request = dict(request or {})
        if operation in self.tools():
            fields = self._schemas()[operation]
            if "workspace_call" not in self.tools():
                # The full profile asks edits for their identity; the core profile fills it in.
                if "request_id" in fields:
                    request.setdefault("request_id", f"script-{uuid.uuid4().hex}")
                if "project_id" in fields and "project_id" not in request:
                    request["project_id"] = self.project()["project_id"]
            data, images = self._tool(operation, {"request": request})
        else:
            arguments = {"operation": operation, "request": request}
            if wait_ms is not None:
                arguments["wait_ms"] = wait_ms
            data, images = self._tool("workspace_call", arguments)
        payload = data.get("data", data)
        while isinstance(payload, dict) and str(payload.get("id", "")).startswith("job-") and "terminal" in payload and not payload["terminal"]:
            data, more = self._tool("job_wait", {"request": {"id": payload["id"], "timeout_ms": 30000}})
            images += more
            payload = data.get("data", data)
        if isinstance(payload, dict) and payload.get("terminal") and str(payload.get("id", "")).startswith("job-"):
            if payload.get("status") != "completed":
                raise McpError(f"{operation} {payload.get('status')}: {payload.get('error', {}).get('message', '')}", payload)
            payload = payload.get("result", payload)
        return payload, images

    def project(self) -> dict:
        return self.call("workspace_inspect", {"scope": "project"})[0]

    def state(self) -> str:
        return self.project()["state"]

    def edit(self, operation: str, **request: object) -> dict:
        """A document edit with the current state filled in; returns the result."""
        request.setdefault("state", self.state())
        return self.call(operation, request)[0]


def _cli(arguments: list[str]) -> int:
    """`call <operation> [json | @file] [--edit] [--out DIR]` and `operations`, for shells and scripts."""
    out = None
    if "--out" in arguments:
        index = arguments.index("--out")
        out = arguments[index + 1]
        del arguments[index:index + 2]
    edit = "--edit" in arguments
    if edit:
        arguments.remove("--edit")
    with Client() as client:
        if arguments[0] == "operations":
            print("\n".join(sorted(client.tools())))
            return 0
        if len(arguments) < 2:
            sys.stderr.write("Usage: mcp_proxy.py call <operation> [json | @file] [--edit] [--out DIR]\n")
            return 2
        raw = arguments[2] if len(arguments) > 2 else "{}"
        request = json.load(open(raw[1:], encoding="utf-8")) if raw.startswith("@") else json.loads(raw)
        try:
            if edit:
                request.setdefault("state", client.state())
            data, images = client.call(arguments[1], request)
        except McpError as error:
            sys.stderr.write(f"{error}\n")
            print(json.dumps(error.detail, ensure_ascii=False, indent=1))
            return 1
        print(json.dumps(data, ensure_ascii=False, indent=1))
        for index, png in enumerate(images):
            directory = out or os.getcwd()
            os.makedirs(directory, exist_ok=True)
            path = os.path.join(directory, f"{arguments[1]}_{int(time.time() * 1000)}_{index}.png")
            with open(path, "wb") as file:
                file.write(png)
            print(f"image {path}")
    return 0


if __name__ == "__main__":
    if len(sys.argv) > 1 and sys.argv[1] in {"call", "operations"}:
        raise SystemExit(_cli(sys.argv[1:]))
    raise SystemExit(main())

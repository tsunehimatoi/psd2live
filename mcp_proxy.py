"""Bridge a stdio MCP client to PSD2Live's Streamable HTTP endpoint.

Use PSD2Live's HTTP endpoint directly whenever the host supports Streamable
HTTP. This dependency-free bridge is a compatibility fallback for stdio-only
hosts. It keeps stdout reserved for line-delimited JSON-RPC messages.
"""

from __future__ import annotations

import json
import os
import sys
import time
import urllib.error
import urllib.request

try:
    import winreg
except ImportError:  # pragma: no cover - only available on Windows
    winreg = None


ENDPOINT = os.environ.get("PSD2LIVE_MCP_ENDPOINT", "http://127.0.0.1:23871/mcp")
REQUEST_TIMEOUT_SECONDS = float(os.environ.get("PSD2LIVE_MCP_TIMEOUT", "60"))
TOKEN_ENV = "PSD2LIVE_MCP_TOKEN"
TRANSIENT_HTTP_STATUSES = {408, 425, 429, 502, 503, 504}
SAFE_PROTOCOL_METHODS = {
    "initialize",
    "ping",
    "tools/list",
    "prompts/list",
    "prompts/get",
    "resources/list",
    "resources/read",
    "resources/templates/list",
    "notifications/initialized",
    "notifications/cancelled",
}
SAFE_PSD2LIVE_TOOLS = {
    "project_get_state",
    "project_list_layers",
    "project_list_parameters",
    "object_get",
    "history_list",
    "task_get",
    "task_list",
    "view_render_layer",
    "view_render_context",
    "view_render_model",
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


def get_token() -> str | None:
    token = os.environ.get(TOKEN_ENV)
    if token:
        return token
    if winreg is None:
        return None
    try:
        with winreg.OpenKey(
            winreg.HKEY_CURRENT_USER,
            r"Software\JavaSoft\Prefs\io\github\psd2live\agent",
        ) as key:
            value, _ = winreg.QueryValueEx(key, "agent_mcp_bearer_token")
        return _decode_java_preferences_value(value)
    except OSError:
        return None


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
    _write_message(json.dumps(parsed, separators=(",", ":"), ensure_ascii=False))


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
                            "then inspect project_get_state/history_list before resuming."
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
                                ". The write commit state may be unknown; reconnect and "
                                "inspect project_get_state/history_list before retrying"
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
                            ". The write commit state is unknown; reconnect and inspect "
                            "project_get_state/history_list before retrying"
                        )
                    sys.stderr.write(summary + "\n")
                    _write_rpc_error(request_id, summary)
                    if method == "initialize":
                        return 1
                    break
    finally:
        _close_session(session_id, token, protocol_version)

    return 0


if __name__ == "__main__":
    raise SystemExit(main())

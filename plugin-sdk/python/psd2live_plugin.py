"""Zero-dependency Python 3 SDK for standalone PSD2Live protocol 2 / 1 modifier plugins."""
from __future__ import annotations

import json
import os
import struct
import sys
from typing import Any, Callable


def load_dependencies() -> list[dict[str, str]]:
    path = os.environ.get("PSD2LIVE_PLUGIN_DEPS")
    if not path or not os.path.isfile(path):
        return []
    with open(path, "r", encoding="utf-8") as f:
        return json.load(f).get("dependencies", [])


def read_f32le(blobs: dict[str, bytes], ref: dict[str, Any]) -> list[float]:
    raw = blobs[ref["inline"]]
    return list(struct.unpack(f"<{len(raw) // 4}f", raw))


def _read_exact(stream: Any, size: int) -> bytes:
    buf = bytearray()
    while len(buf) < size:
        chunk = stream.read(size - len(buf))
        if not chunk:
            raise EOFError("Unexpected EOF reading protocol 2 frame")
        buf.extend(chunk)
    return bytes(buf)


def _read_frame(stream: Any) -> tuple[dict[str, Any], dict[str, bytes]] | None:
    first = stream.read(4)
    if not first:
        return None
    if len(first) < 4:
        first += _read_exact(stream, 4 - len(first))
    (header_len,) = struct.unpack("<I", first)
    header = json.loads(_read_exact(stream, header_len).decode("utf-8"))
    (blob_count,) = struct.unpack("<I", _read_exact(stream, 4))
    blobs: dict[str, bytes] = {}
    for _ in range(blob_count):
        (key_len,) = struct.unpack("<I", _read_exact(stream, 4))
        key = _read_exact(stream, key_len).decode("utf-8")
        (data_len,) = struct.unpack("<I", _read_exact(stream, 4))
        blobs[key] = _read_exact(stream, data_len)
    return header, blobs


def _write_frame(stream: Any, header: dict[str, Any], blobs: dict[str, bytes] | None = None) -> None:
    header_bytes = json.dumps(header, separators=(",", ":"), ensure_ascii=False).encode("utf-8")
    stream.write(struct.pack("<I", len(header_bytes)))
    stream.write(header_bytes)
    items = list((blobs or {}).items())
    stream.write(struct.pack("<I", len(items)))
    for key, data in items:
        key_bytes = key.encode("utf-8")
        stream.write(struct.pack("<I", len(key_bytes)))
        stream.write(key_bytes)
        stream.write(struct.pack("<I", len(data)))
        stream.write(data)
    stream.flush()


def run(handler: Callable[[dict[str, Any], dict[str, bytes]], dict[str, Any]]) -> None:
    protocol = 2 if os.environ.get("PSD2LIVE_PLUGIN_PROTOCOL") == "2" else 1
    if protocol == 2:
        stdin = sys.stdin.buffer
        stdout = sys.stdout.buffer
        while True:
            frame = _read_frame(stdin)
            if frame is None:
                break
            header, blobs = frame
            try:
                result = handler(header, blobs)
                envelope = {"protocol": 2, "id": header["id"], **result}
            except Exception as exc:
                envelope = {"protocol": 2, "id": header.get("id", 0), "error": str(exc)}
            _write_frame(stdout, envelope)
    else:
        for line in sys.stdin:
            line = line.strip()
            if not line:
                continue
            header = json.loads(line)
            try:
                result = handler(header, {})
                envelope = {"protocol": 1, "id": header["id"], **result}
            except Exception as exc:
                envelope = {"protocol": 1, "id": header.get("id", 0), "error": str(exc)}
            sys.stdout.write(json.dumps(envelope, separators=(",", ":"), ensure_ascii=False) + "\n")
            sys.stdout.flush()

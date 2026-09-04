import io
import json
import os
import unittest
import urllib.error
from contextlib import redirect_stderr, redirect_stdout
from unittest.mock import patch

import mcp_proxy


class McpProxyTest(unittest.TestCase):
    def test_retry_policy_distinguishes_reads_from_mutations(self):
        read = {
            "jsonrpc": "2.0",
            "id": 1,
            "method": "tools/call",
            "params": {"name": "project_get_state", "arguments": {}},
        }
        mutation = {
            "jsonrpc": "2.0",
            "id": 2,
            "method": "tools/call",
            "params": {"name": "layer_soft_delete", "arguments": {}},
        }

        self.assertTrue(mcp_proxy._is_retry_safe(read))
        self.assertFalse(mcp_proxy._is_retry_safe(mutation))

    def test_sse_payload_is_emitted_as_one_json_rpc_line(self):
        body = (
            'event: message\n'
            'data: {"jsonrpc":"2.0",\n'
            'data: "id":1,"result":{"ok":true}}\n\n'
        )
        output = io.StringIO()
        with redirect_stdout(output):
            mcp_proxy._write_http_body(body, "text/event-stream")

        lines = output.getvalue().splitlines()
        self.assertEqual(1, len(lines))
        self.assertEqual(True, json.loads(lines[0])["result"]["ok"])

    def test_network_failure_retries_a_read_once_but_not_a_mutation(self):
        def run(message):
            stdin = io.StringIO(json.dumps(message) + "\n")
            stdout = io.StringIO()
            stderr = io.StringIO()
            failure = urllib.error.URLError("offline")
            with (
                patch.dict(os.environ, {mcp_proxy.TOKEN_ENV: "t" * 40}),
                patch("sys.stdin", stdin),
                patch("mcp_proxy.urllib.request.urlopen", side_effect=failure) as urlopen,
                patch("mcp_proxy.time.sleep"),
                redirect_stdout(stdout),
                redirect_stderr(stderr),
            ):
                exit_code = mcp_proxy.main()
            return exit_code, urlopen.call_count, json.loads(stdout.getvalue())

        read_result = run(
            {
                "jsonrpc": "2.0",
                "id": 1,
                "method": "tools/call",
                "params": {"name": "history_list", "arguments": {}},
            }
        )
        mutation_result = run(
            {
                "jsonrpc": "2.0",
                "id": 2,
                "method": "tools/call",
                "params": {"name": "keyform_set", "arguments": {}},
            }
        )

        self.assertEqual((0, 2), read_result[:2])
        self.assertEqual((0, 1), mutation_result[:2])
        self.assertIn("commit state is unknown", mutation_result[2]["error"]["message"])


if __name__ == "__main__":
    unittest.main()

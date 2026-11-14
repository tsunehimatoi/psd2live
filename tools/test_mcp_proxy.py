import sys
import unittest
from pathlib import Path
from unittest.mock import Mock

sys.path.insert(0, str(Path(__file__).resolve().parents[0]))
from mcp_proxy import Client, McpError


class ClientJobTest(unittest.TestCase):
    def client(self, tools):
        client = Client(token="test-token")
        client._tools = {name: {} for name in tools}
        return client

    def test_core_jobs_use_the_gateway_for_every_poll(self):
        client = self.client(["workspace_call", "job"])
        running = {"ok": True, "data": {"id": "job-1", "terminal": False}}
        done = {"ok": True, "data": {"id": "job-1", "terminal": True,
                                     "status": "completed", "result": {"state": "next"}}}
        client._tool = Mock(side_effect=[(running, [b"first"]), (running, []), (done, [b"last"])])
        self.assertEqual(client.call("project_create", {"state": "old"}, wait_ms=0),
                         ({"state": "next"}, [b"first", b"last"]))
        self.assertEqual(client._tool.call_args_list[0].args,
                         ("workspace_call", {"operation": "project_create", "request": {"state": "old"}, "wait_ms": 0}))
        for call in client._tool.call_args_list[1:]:
            self.assertEqual(call.args, ("workspace_call", {"operation": "job_wait",
                                                          "request": {"id": "job-1", "timeout_ms": 30000}}))

    def test_full_jobs_use_the_published_wait_tool(self):
        client = self.client(["project_create", "job_wait"])
        client._tool = Mock(side_effect=[
            ({"data": {"id": "job-2", "terminal": False}}, []),
            ({"data": {"id": "job-2", "terminal": True, "status": "completed", "result": {"ok": 1}}}, [])])
        self.assertEqual(client.call("project_create"), ({"ok": 1}, []))
        self.assertEqual(client._tool.call_args.args,
                         ("job_wait", {"request": {"id": "job-2", "timeout_ms": 30000}}))

    def test_failed_job_keeps_its_error(self):
        client = self.client(["workspace_call"])
        failure = {"id": "job-3", "terminal": True, "status": "failed", "error": {"message": "failed edit"}}
        client._tool = Mock(side_effect=[({"data": {"id": "job-3", "terminal": False}}, []), ({"data": failure}, [])])
        with self.assertRaises(McpError) as raised:
            client.call("project_create")
        self.assertIn("failed edit", str(raised.exception))


if __name__ == "__main__":
    unittest.main()

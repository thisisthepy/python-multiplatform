"""The dev agent's protocol (#233; SPEC L-12), with no UI and no Kotlin: `python3 -m unittest` from this
directory. The agent is `src/commonMain/python/python_multiplatform_devagent.py`, the same file the runtime
embeds.

Each test starts an agent on an ephemeral loopback port in this process (as the app would, in its own
interpreter) and talks to it over a real socket, one JSON object per line.
"""
import json
import os
import socket
import sys
import tempfile
import textwrap
import types
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "..", "..", "commonMain", "python"))

import python_multiplatform_devagent as devagent  # noqa: E402

# secrets.token_urlsafe(32): the shortest token the agent accepts is this long.
TOKEN = "dGVzdC10b2tlbi1mb3ItdGhlLWRldi1hZ2VudC10ZXN0cw"


class Client:
    def __init__(self, port: int):
        self.sock = socket.create_connection(("127.0.0.1", port), timeout=10)
        self.reader = self.sock.makefile("r", encoding="utf-8")
        self.next_id = 0

    def send(self, op: str, **fields) -> int:
        self.next_id += 1
        message = {"id": self.next_id, "op": op, **fields}
        self.sock.sendall((json.dumps(message) + "\n").encode("utf-8"))
        return self.next_id

    def read(self) -> dict:
        line = self.reader.readline()
        if not line:
            raise ConnectionError("the agent closed the connection")
        return json.loads(line)

    def request(self, op: str, **fields) -> list:
        """Every message answering this request, up to and including its `done`."""
        request_id = self.send(op, **fields)
        replies = []
        while True:
            reply = self.read()
            self.assert_id(reply, request_id)
            replies.append(reply)
            if reply["type"] in ("done", "hello", "error"):
                return replies

    @staticmethod
    def assert_id(reply: dict, request_id: int):
        if reply.get("id") != request_id:
            raise AssertionError(f"reply {reply} does not answer request {request_id}")

    def close(self):
        self.reader.close()
        self.sock.close()


class AgentTestCase(unittest.TestCase):
    def setUp(self):
        self.overlay = tempfile.mkdtemp(prefix="devagent-overlay-")
        self.agent = devagent.start(port=0, token=TOKEN, overlay=self.overlay)
        self.client = Client(self.agent.port)
        hello = self.client.request("hello", token=TOKEN)
        self.assertEqual("hello", hello[-1]["type"])

    def tearDown(self):
        self.client.close()
        self.agent.stop()
        for name in [n for n in sys.modules if n.startswith("devagent_fixture")]:
            del sys.modules[name]
        if self.overlay in sys.path:
            sys.path.remove(self.overlay)

    def execute(self, code: str) -> list:
        return self.client.request("execute", code=textwrap.dedent(code))

    @staticmethod
    def done(replies: list) -> dict:
        return replies[-1]

    @staticmethod
    def of_type(replies: list, kind: str) -> list:
        return [r for r in replies if r["type"] == kind]


class Authentication(unittest.TestCase):
    def test_a_wrong_token_is_refused_and_the_connection_closed(self):
        agent = devagent.start(port=0, token=TOKEN, overlay=tempfile.mkdtemp())
        try:
            client = Client(agent.port)
            reply = client.request("hello", token="wrong")[-1]
            self.assertEqual("error", reply["type"])
            self.assertEqual("AuthError", reply["ename"])
            self.assertEqual("", client.reader.readline(), "the agent closes the connection")
            client.close()
        finally:
            agent.stop()

    def test_a_request_before_hello_is_refused(self):
        agent = devagent.start(port=0, token=TOKEN, overlay=tempfile.mkdtemp())
        try:
            client = Client(agent.port)
            reply = client.request("execute", code="1")[-1]
            self.assertEqual("AuthError", reply["ename"])
            client.close()
        finally:
            agent.stop()

    def test_it_listens_on_loopback_only(self):
        agent = devagent.start(port=0, token=TOKEN, overlay=tempfile.mkdtemp())
        try:
            self.assertEqual("127.0.0.1", agent.host)
        finally:
            agent.stop()

    def test_the_request_comes_from_the_environment(self):
        self.assertEqual(
            {"port": 4719, "token": TOKEN, "overlay": "/tmp/o"},
            devagent.parse_request(f"port=4719;token={TOKEN};overlay=/tmp/o"),
        )
        self.assertEqual({"port": 4719, "token": TOKEN, "overlay": None}, devagent.parse_request(f"port=4719;token={TOKEN}"))
        for bad in ["", f"port=x;token={TOKEN}", "port=1", f"token={TOKEN}"]:
            with self.assertRaises(ValueError, msg=bad):
                devagent.parse_request(bad)

    def test_a_short_token_is_refused_before_anything_listens(self):
        short = TOKEN[: devagent.MIN_TOKEN_LENGTH - 1]
        with self.assertRaises(ValueError):
            devagent.parse_request(f"port=4719;token={short}")
        with self.assertRaises(ValueError):
            devagent.start(port=0, token=short, overlay=tempfile.mkdtemp())
        self.assertGreaterEqual(devagent.MIN_TOKEN_LENGTH, len(__import__("secrets").token_urlsafe(32)))

    def test_another_socket_cannot_take_the_port_over(self):
        agent = devagent.start(port=0, token=TOKEN, overlay=tempfile.mkdtemp())
        try:
            options = [socket.SO_REUSEADDR] + ([socket.SO_REUSEPORT] if hasattr(socket, "SO_REUSEPORT") else [])
            for option in options:
                hijacker = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
                try:
                    hijacker.setsockopt(socket.SOL_SOCKET, option, 1)
                    with self.assertRaises(OSError, msg=f"option {option}"):
                        hijacker.bind(("127.0.0.1", agent.port))
                finally:
                    hijacker.close()
        finally:
            agent.stop()

    def test_a_restarted_agent_gets_its_port_back(self):
        # A launcher reuses the port across app restarts, so the closed agent's connections in TIME_WAIT
        # must not keep the next one from binding.
        agent = devagent.start(port=0, token=TOKEN, overlay=tempfile.mkdtemp())
        port = agent.port
        client = Client(port)
        client.request("hello", token=TOKEN)
        agent.stop()
        client.close()
        again = devagent.start(port=port, token=TOKEN, overlay=tempfile.mkdtemp())
        again.stop()


class Execute(AgentTestCase):
    def test_the_hello_reply_names_the_protocol(self):
        client = Client(self.agent.port)
        hello = client.request("hello", token=TOKEN)[-1]
        self.assertEqual(1, hello["protocol"])
        self.assertIn("python", hello)
        client.close()

    def test_the_last_expression_is_the_result(self):
        self.execute("x_devagent = 40")
        replies = self.execute("x_devagent + 2")
        self.assertEqual("ok", self.done(replies)["status"])
        self.assertEqual("42", self.of_type(replies, "result")[0]["data"]["text/plain"])

    def test_the_namespace_is_main_and_persists(self):
        self.execute("import sys\nsys.modules['__main__'].devagent_marker = 'kept'")
        replies = self.execute("devagent_marker")
        self.assertEqual("'kept'", self.of_type(replies, "result")[0]["data"]["text/plain"])

    def test_stdout_and_stderr_are_streamed(self):
        replies = self.execute("import sys\nprint('out line')\nprint('err line', file=sys.stderr)")
        streams = {r["name"]: "" for r in self.of_type(replies, "stream")}
        for r in self.of_type(replies, "stream"):
            streams[r["name"]] += r["text"]
        self.assertEqual("out line\n", streams["stdout"])
        self.assertEqual("err line\n", streams["stderr"])
        self.assertEqual([], self.of_type(replies, "result"), "a print returns nothing")

    def test_an_error_is_reported_with_its_traceback(self):
        replies = self.execute("1 / 0")
        done = self.done(replies)
        self.assertEqual("error", done["status"])
        self.assertEqual("ZeroDivisionError", done["ename"])
        self.assertTrue(any("ZeroDivisionError" in line for line in done["traceback"]), done["traceback"])

    def test_a_syntax_error_is_reported(self):
        done = self.done(self.execute("def ("))
        self.assertEqual("SyntaxError", done["ename"])

    def test_the_execution_count_grows(self):
        first = self.done(self.execute("1"))["count"]
        second = self.done(self.execute("2"))["count"]
        self.assertEqual(first + 1, second)

    def test_completion_offers_names_in_the_namespace(self):
        self.execute("devagent_completion_target = 1")
        done = self.done(self.client.request("complete", code="devagent_comp", cursor_pos=13))
        self.assertIn("devagent_completion_target", done["matches"])
        self.assertEqual(0, done["cursor_start"])
        self.assertEqual(13, done["cursor_end"])

    def test_an_unknown_op_is_an_error_and_the_connection_stays(self):
        done = self.done(self.client.request("teleport"))
        self.assertEqual("error", done["status"])
        self.assertEqual("ok", self.done(self.execute("1"))["status"])


class Push(AgentTestCase):
    def test_pushed_files_land_in_the_overlay_which_is_first_on_sys_path(self):
        done = self.done(self.client.request("push", files={"devagent_fixture_a.py": "VALUE = 1\n"}))
        self.assertEqual("ok", done["status"])
        self.assertEqual(self.overlay, sys.path[0])
        self.assertTrue(os.path.isfile(os.path.join(self.overlay, "devagent_fixture_a.py")))
        replies = self.execute("import devagent_fixture_a\ndevagent_fixture_a.VALUE")
        self.assertEqual("1", self.of_type(replies, "result")[0]["data"]["text/plain"])

    def test_a_loaded_module_is_reimported_and_reported(self):
        self.client.request("push", files={"devagent_fixture_b.py": "VALUE = 1\n"})
        self.execute("import devagent_fixture_b")
        done = self.done(self.client.request("push", files={"devagent_fixture_b.py": "VALUE = 2\n"}))
        self.assertEqual("ok", done["status"])
        self.assertEqual(["devagent_fixture_b"], done["reloaded"])
        replies = self.execute("devagent_fixture_b.VALUE")
        self.assertEqual("2", self.of_type(replies, "result")[0]["data"]["text/plain"])

    def test_a_package_module_and_its_package_are_reimported_children_first(self):
        files = {"devagent_fixture_pkg/__init__.py": "", "devagent_fixture_pkg/mod.py": "VALUE = 1\n"}
        self.client.request("push", files=files)
        self.execute("import devagent_fixture_pkg.mod")
        done = self.done(self.client.request("push", files={"devagent_fixture_pkg/mod.py": "VALUE = 3\n"}))
        self.assertEqual(["devagent_fixture_pkg.mod"], done["reloaded"])
        replies = self.execute("devagent_fixture_pkg.mod.VALUE")
        self.assertEqual("3", self.of_type(replies, "result")[0]["data"]["text/plain"])

    def test_a_reload_that_fails_keeps_the_old_module(self):
        self.client.request("push", files={"devagent_fixture_c.py": "VALUE = 1\n"})
        self.execute("import devagent_fixture_c")
        done = self.done(self.client.request("push", files={"devagent_fixture_c.py": "VALUE = (\n"}))
        self.assertEqual("error", done["status"])
        self.assertEqual("SyntaxError", done["ename"])
        replies = self.execute("devagent_fixture_c.VALUE")
        self.assertEqual("1", self.of_type(replies, "result")[0]["data"]["text/plain"])

    def test_a_path_outside_the_overlay_is_refused(self):
        # Windows forms too, on every OS: on Windows `os.path.isabs("/abs/escape.py")` is False (Python 3.13+)
        # and joining it to the overlay lands on the drive root, so the rule cannot be os.path's.
        for path in ["../escape.py", "/abs/escape.py", "a/../../escape.py", "C:/escape.py", "C:escape.py",
                     "\\escape.py", "a\\..\\..\\escape.py", "//server/share/escape.py", "./a.py", "a//b.py", ""]:
            done = self.done(self.client.request("push", files={path: "X = 1\n"}))
            self.assertEqual("error", done["status"], path)
            self.assertEqual("ValueError", done["ename"], path)


class ImportGate(unittest.TestCase):
    """N-7b: when `typedpython.importgate` is in the app, a reload goes through it."""

    def setUp(self):
        self.installed = []
        gate_errors = []

        class ImportGateError(ImportError):
            pass

        class Finder:
            """Refuses any module under a root whose source contains the word REJECT."""

            def __init__(self, roots):
                self.roots = roots

            def find_spec(self, name, path=None, target=None):
                import importlib.machinery
                spec = importlib.machinery.PathFinder.find_spec(name, path)
                if spec and spec.origin and any(spec.origin.startswith(r) for r in self.roots):
                    with open(spec.origin, encoding="utf-8") as f:
                        if "REJECT" in f.read():
                            gate_errors.append(name)
                            raise ImportGateError(f"typedpython: {name} does not type-check")
                return None

        def install(roots):
            finder = Finder(list(roots))
            sys.meta_path.insert(0, finder)
            self.installed.append(finder)

        def uninstall():
            for finder in self.installed:
                if finder in sys.meta_path:
                    sys.meta_path.remove(finder)

        package = types.ModuleType("typedpython")
        importgate = types.ModuleType("typedpython.importgate")
        importgate.install = install
        importgate.uninstall = uninstall
        importgate.ImportGateError = ImportGateError
        package.importgate = importgate
        sys.modules["typedpython"] = package
        sys.modules["typedpython.importgate"] = importgate
        self.gate_errors = gate_errors

        self.overlay = tempfile.mkdtemp(prefix="devagent-gated-")
        self.agent = devagent.start(port=0, token=TOKEN, overlay=self.overlay)
        self.client = Client(self.agent.port)
        self.client.request("hello", token=TOKEN)

    def tearDown(self):
        self.client.close()
        self.agent.stop()
        sys.modules["typedpython.importgate"].uninstall()
        for name in ["typedpython", "typedpython.importgate", "devagent_fixture_gated"]:
            sys.modules.pop(name, None)
        if self.overlay in sys.path:
            sys.path.remove(self.overlay)

    def test_the_gate_is_installed_for_the_overlay(self):
        self.assertEqual([[self.overlay]], [f.roots for f in self.installed])

    def test_a_reload_the_gate_rejects_keeps_the_old_module(self):
        self.client.request("push", files={"devagent_fixture_gated.py": "VALUE = 1\n"})
        self.client.request("execute", code="import devagent_fixture_gated")
        done = self.client.request("push", files={"devagent_fixture_gated.py": "VALUE = 2  # REJECT\n"})[-1]
        self.assertEqual("error", done["status"])
        self.assertEqual("ImportGateError", done["ename"])
        self.assertIn("does not type-check", done["evalue"])
        self.assertEqual(["devagent_fixture_gated"], self.gate_errors)
        replies = self.client.request("execute", code="devagent_fixture_gated.VALUE")
        self.assertEqual("1", [r for r in replies if r["type"] == "result"][0]["data"]["text/plain"])


if __name__ == "__main__":
    unittest.main()

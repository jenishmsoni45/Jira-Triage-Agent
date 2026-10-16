import json
import os
from pathlib import Path
from queue import Empty, Queue
import subprocess
from threading import Thread
import time


def main():
    root = Path(__file__).resolve().parents[1]
    jar = root / "target" / "jira-triage-agent-0.1.0-SNAPSHOT.jar"
    if not jar.is_file():
        raise SystemExit("Build the application first with: mvn -o verify")
    java_home = os.environ.get("JAVA_HOME")
    java = str(Path(java_home) / "bin" / "java") if java_home else "java"
    process = subprocess.Popen(
        [java, "-jar", str(jar), "--triage.gateway=fixture"],
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        stderr=None,
        text=True,
        encoding="utf-8",
    )
    lines = Queue()

    def read_stdout():
        try:
            for line in process.stdout:
                lines.put(line)
        finally:
            lines.put(None)

    reader = Thread(target=read_stdout, daemon=True)
    reader.start()
    next_id = 0

    def send(message):
        process.stdin.write(json.dumps(message) + "\n")
        process.stdin.flush()

    def request(method, params):
        nonlocal next_id
        next_id += 1
        send({"jsonrpc": "2.0", "id": next_id, "method": method, "params": params})
        deadline = time.monotonic() + 10
        while True:
            try:
                line = lines.get(timeout=max(0, deadline - time.monotonic()))
            except Empty as error:
                raise RuntimeError(f"Timed out waiting for {method}; inspect stderr") from error
            if line is None:
                raise RuntimeError("Server closed stdout before returning a response")
            response = json.loads(line)
            if response.get("jsonrpc") != "2.0":
                raise RuntimeError("Non-protocol output on server stdout")
            if "id" not in response:
                continue
            if response["id"] != next_id or "error" in response:
                raise RuntimeError(f"Unexpected protocol response: {response}")
            return response["result"]

    def call(name, arguments):
        result = request("tools/call", {"name": name, "arguments": arguments})
        print(json.dumps({"tool": name, "arguments": arguments, "result": result}, indent=2))
        if result.get("isError"):
            raise RuntimeError(f"Tool failed: {name}")
        return result["structuredContent"]["data"]

    try:
        request("initialize", {
            "protocolVersion": "2025-11-25", "capabilities": {},
            "clientInfo": {"name": "local-demo", "version": "1.0"},
        })
        send({"jsonrpc": "2.0", "method": "notifications/initialized"})
        print(json.dumps({"tools": request("tools/list", {})}, indent=2))
        call("search_issues", {"query": "password"})
        original = call("get_issue", {"issueKey": "DEMO-1"})["issue"]
        call("triage_issue", {"issueKey": "DEMO-1"})
        call("find_duplicates", {"issueKey": "DEMO-1"})
        update = {
            "issueKey": "DEMO-2",
            "fields": {
                "summary": original["summary"].upper(),
                "description": original["description"] + " Reproduced after refreshing the browser.",
            },
        }
        call("update_issue", update)
        call("update_issue", {**update, "dryRun": False})
        call("find_duplicates", {"issueKey": "DEMO-1"})
        link = {"issueKey": "DEMO-2", "duplicateOfKey": "DEMO-1"}
        call("link_duplicate", link)
        call("link_duplicate", {**link, "dryRun": False})
        call("link_duplicate", {**link, "dryRun": False})
        call("get_issue", {"issueKey": "DEMO-2"})
    finally:
        process.stdin.close()
        try:
            process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait()
        reader.join(timeout=2)
        process.stdout.close()
    if process.returncode != 0:
        raise SystemExit(process.returncode)


if __name__ == "__main__":
    main()

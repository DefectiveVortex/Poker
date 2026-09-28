#!/usr/bin/env python3
"""A stand-in for Modrinth's API, so the Poker updater can be tested without a published project.

    python3 test-bot/modrinth-mock.py --jar /path/to/some.jar [--version 1.0.1] [--bad-hash]
                                      [--bind 172.17.0.1] [--port 25590] [--status 200]

Point the plugin at it with `updates.api-url: http://172.17.0.1:25590/v2` in plugins/Poker/config.yml
(172.17.0.1 is the host as seen from the poker-test container), restart, then `poker update` over RCON.

  GET /v2/project/<id>/version   one version: --version, type release, loaders bukkit/spigot/paper/purpur,
                                 game_versions = whatever the plugin asked for (so it fits any server),
                                 primary file /files/Poker-<version>.jar with the jar's sha512
                                 (or a wrong one with --bad-hash). --status 404 answers 404 instead.
  GET /files/<name>              the --jar bytes
  GET /mock?version=1.0.2&bad=1&status=404   change any of those live; answers the current state
  GET /mock/log                  every request so far as JSON: [{"path", "user_agent"}]

Every request is also printed to stdout with its User-Agent.
"""
import argparse
import hashlib
import json
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

state = {"version": "1.0.1", "bad": False, "status": 200}
jar = b""
requests = []


def version_json(host, game_versions):
    good = hashlib.sha512(jar).hexdigest()
    sha = ("0" * 128) if state["bad"] else good
    name = "Poker-%s.jar" % state["version"]
    return [{
        "id": "mock" + state["version"].replace(".", ""),
        "project_id": "JdpGQMcC",
        "name": "Poker " + state["version"],
        "version_number": state["version"],
        "version_type": "release",
        "status": "listed",
        "game_versions": game_versions,
        "loaders": ["bukkit", "spigot", "paper", "purpur"],
        "date_published": "2026-10-01T00:00:00.000000Z",
        "files": [{
            "hashes": {"sha512": sha, "sha1": hashlib.sha1(jar).hexdigest()},
            "url": "http://%s/files/%s" % (host, name),
            "filename": name,
            "primary": True,
            "size": len(jar),
        }],
        "dependencies": [],
    }]


class Handler(BaseHTTPRequestHandler):
    def send(self, status, body=b"", ctype="application/json"):
        self.send_response(status)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        url = urlparse(self.path)
        query = parse_qs(url.query)
        ua = self.headers.get("User-Agent", "")
        if not url.path.startswith("/mock"):
            requests.append({"path": url.path, "user_agent": ua})
            print("%s  UA=%s" % (self.path, ua), flush=True)

        if url.path == "/mock/log":
            return self.send(200, json.dumps(requests).encode())
        if url.path == "/mock":
            if "version" in query:
                state["version"] = query["version"][0]
            if "bad" in query:
                state["bad"] = query["bad"][0] not in ("0", "false", "")
            if "status" in query:
                state["status"] = int(query["status"][0])
            print("mock state now %s" % state, flush=True)
            return self.send(200, json.dumps(state).encode())
        if url.path.startswith("/v2/project/") and url.path.endswith("/version"):
            if state["status"] != 200:
                return self.send(state["status"], b'{"error":"not_found","description":"mock"}')
            try:
                games = json.loads(query.get("game_versions", ["[]"])[0])
            except ValueError:
                games = []
            host = self.headers.get("Host") or "%s:%d" % self.server.server_address[:2]
            return self.send(200, json.dumps(version_json(host, games or ["1.20.1", "26.3"])).encode())
        if url.path.startswith("/files/"):
            return self.send(200, jar, "application/java-archive")
        self.send(404, b'{"error":"not_found"}')

    def log_message(self, *args):
        pass


def main():
    global jar
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--jar", required=True, help="bytes to serve as the new version")
    p.add_argument("--version", default="1.0.1")
    p.add_argument("--bad-hash", action="store_true", help="advertise a wrong sha512")
    p.add_argument("--status", type=int, default=200, help="answer the version list with this status (e.g. 404)")
    p.add_argument("--bind", default="172.17.0.1")
    p.add_argument("--port", type=int, default=25590)
    a = p.parse_args()
    with open(a.jar, "rb") as f:
        jar = f.read()
    state.update(version=a.version, bad=a.bad_hash, status=a.status)
    server = ThreadingHTTPServer((a.bind, a.port), Handler)
    print("modrinth mock on http://%s:%d/v2  state=%s  jar=%s (%d bytes)" % (a.bind, a.port, state, a.jar, len(jar)),
          flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    return 0


if __name__ == "__main__":
    sys.exit(main())

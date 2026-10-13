"""Call one MCP tool through the bridge: python dev/call_tool.py <tool> '<json args>' [image-prefix]
Prints text content (FULL=1 for untruncated); saves images as <image-prefix>-<n>.png in the current directory.
Needs a release bridge (cd bridge && cargo build --release) and a running game (dev/restart_client.sh)."""
import base64, json, os, subprocess, sys

here = os.getcwd()
root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
bridge = os.path.join(root, "bridge", "target", "release", "mc-mcp-bridge" + (".exe" if os.name == "nt" else ""))
token = json.load(open(os.path.join(root, "mod", "run", "config", "mcmcp.json")))["token"]
env = dict(os.environ, MCMCP_TOKEN=token, MCMCP_LOG="warn")
p = subprocess.Popen([bridge], stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True, encoding="utf-8",
                     env=env, cwd=root)

def rpc(i, method, params):
    p.stdin.write(json.dumps({"jsonrpc": "2.0", "id": i, "method": method, "params": params}) + "\n")
    p.stdin.flush()
    while True:
        m = json.loads(p.stdout.readline())
        if m.get("id") == i:
            return m

rpc(1, "initialize", {"protocolVersion": "2025-06-18", "capabilities": {}, "clientInfo": {"name": "cli", "version": "0"}})
p.stdin.write('{"jsonrpc":"2.0","method":"notifications/initialized"}\n'); p.stdin.flush()
r = rpc(2, "tools/call", {"name": sys.argv[1], "arguments": json.loads(sys.argv[2]) if len(sys.argv) > 2 else {}})
res = r.get("result", r)
if res.get("isError"):
    print("ERROR:", end=" ")
n = 0
for c in res.get("content", []):
    if c["type"] == "text":
        t = c["text"]
        print(t if len(t) < 3000 or os.environ.get("FULL") else t[:3000] + "...")
    elif c["type"] == "image":
        n += 1
        prefix = sys.argv[3] if len(sys.argv) > 3 else "shot"
        path = os.path.join(here, f"{prefix}-{n}.png")
        open(path, "wb").write(base64.b64decode(c["data"]))
        print("saved", path)
p.stdin.close()

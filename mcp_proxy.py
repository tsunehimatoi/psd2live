import sys
import json
import urllib.request
import winreg

ENDPOINT = "http://127.0.0.1:23871/mcp"

def get_token():
    try:
        key = winreg.OpenKey(winreg.HKEY_CURRENT_USER, r"Software\JavaSoft\Prefs\io\github\psd2live\agent")
        val, _ = winreg.QueryValueEx(key, "agent_mcp_bearer_token")
        res = []
        i = 0
        while i < len(val):
            if val[i] == '/':
                i += 1
                if i < len(val):
                    res.append(val[i].upper() if val[i] != '/' else '/')
            else:
                res.append(val[i])
            i += 1
        return "".join(res)
    except Exception:
        return None

def main():
    token = get_token()
    session_id = None
    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        headers = {
            "Content-Type": "application/json",
        }
        if token:
            headers["Authorization"] = f"Bearer {token}"
        if session_id:
            headers["mcp-session-id"] = session_id
        req = urllib.request.Request(ENDPOINT, data=line.encode("utf-8"), headers=headers, method="POST")
        try:
            with urllib.request.urlopen(req) as resp:
                new_session = resp.headers.get("mcp-session-id")
                if new_session:
                    session_id = new_session
                body = resp.read().decode("utf-8")
                if body.strip():
                    sys.stdout.write(body.strip() + "\n")
                    sys.stdout.flush()
        except urllib.error.HTTPError as err:
            err_body = err.read().decode("utf-8")
            sys.stderr.write(f"HTTP {err.code}: {err_body}\n")
            sys.stderr.flush()
        except Exception as e:
            sys.stderr.write(f"MCP error: {e}\n")
            sys.stderr.flush()

if __name__ == "__main__":
    main()

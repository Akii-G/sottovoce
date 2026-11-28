"""Mac only (run by .github/workflows/macos.yml): the phone receiver and the daily reminder are
launchd agents, a notification can be shown, and the folders are in Application Support."""
import os, sys, tempfile, time, plistlib, subprocess, importlib.machinery, importlib.util
if sys.platform != "darwin":
    print("mac: skipped (not a Mac)")
    sys.exit(0)
data = tempfile.mkdtemp(); cfgdir = tempfile.mkdtemp()
os.environ.update(MOODLOG_DATA_DIR=data, MOODLOG_CONFIG_DIR=cfgdir, MOODLOG_LANG="en")
here = os.path.dirname(os.path.abspath(__file__))
script = os.path.join(here, "..", "src", "moodlog")
sys.argv = [script]
loader = importlib.machinery.SourceFileLoader("moodlog", script)
spec = importlib.util.spec_from_loader("moodlog", loader); m = importlib.util.module_from_spec(spec); loader.exec_module(m)

name = m.local_host_name()
print("name on the Wi-Fi:", name + ".local")
assert name and "." not in name, name

def where_stuck(label):
    """What a stuck launchd agent is doing: macOS's `sample` of its process."""
    import re as _re
    out = subprocess.run(["launchctl", "print", "gui/%d/%s" % (os.getuid(), label)], capture_output=True, text=True).stdout
    pid = _re.search(r"\bpid = (\d+)", out)
    if not pid:
        return "not running:\n" + out[-1500:]
    s = subprocess.run(["sample", pid.group(1), "2"], capture_output=True, text=True).stdout
    lines = [l for l in s.splitlines() if "Thread_" in l or "+" in l][:120]
    return "\n".join(lines)


assert m.IS_MAC and m.default_style() == "tahoe"
print("dirs:", m.default_data_dir(), m.default_config_dir())
assert "Library/Application Support/Sottovoce" in m.default_data_dir()
assert m.default_data_dir() != m.default_config_dir()
assert "Library/Application Support/Dose" in m.dose_path() or os.environ.get("DOSE_DATA_DIR")
print("system language:", m.system_language())

ok, err = m.receiver_set(True)
assert ok, err
agent = plistlib.load(open(m.mac_agent_path(m.MAC_SYNC_LABEL), "rb"))
assert agent["ProgramArguments"][-1] == "--sync-server" and agent["KeepAlive"], agent
for _ in range(40):
    if m.receiver_running():
        break
    time.sleep(0.5)
if not m.receiver_running():
    log = os.path.join(cfgdir, "sync.log")
    print(open(log).read()[-4000:] if os.path.exists(log) else "no log")
    print(where_stuck(m.MAC_SYNC_LABEL))
    raise SystemExit("the receiver isn't listening")

# a whole phone's history over the network, as Sottovoce for Android sends it: all of it arrives
import urllib.request, datetime as dt
token = m.sync_config()["token"]; port = m.sync_config()["port"]
entries = [{"id": "a%d" % i, "date": (dt.date(2025, 1, 1) + dt.timedelta(days=i // 2)).isoformat(),
            "time": "%02d:00" % (8 + 10 * (i % 2)), "mood": 1 + i % 5, "energy": i % 6, "anxiety": 0,
            "note": "nota %d è" % i if i % 3 == 0 else "", "updated": "2026-11-20T10:00:00.000Z",
            "feelings": []} for i in range(300)]
doc = {"app": "android", "entries": entries, "events": [], "deleted": [], "dreams": []}
body = m.seal(token, "/api/e/sync", doc)
req = urllib.request.Request("http://127.0.0.1:%d/api/e/sync" % port, data=body.encode() if isinstance(body, str) else body,
                             headers={"Content-Type": "application/json"}, method="POST")
with urllib.request.urlopen(req, timeout=30) as r:
    state = m.unseal(token, "/api/e/sync", r.read(), reply=True)
assert state["changed"] == 300, state["changed"]
assert sum(len(v) for v in m.Store().days.values()) == 300
print("300 entries over the network: OK")

ok, err = m.receiver_set(False)
assert ok, err
for _ in range(20):
    if not m.receiver_running():
        break
    time.sleep(0.5)
assert not m.receiver_running() and not os.path.exists(m.mac_agent_path(m.MAC_SYNC_LABEL))

ok, err = m.mac_apply_reminder(True, "20:30")
assert ok, err
agent = plistlib.load(open(m.mac_agent_path(m.MAC_REMIND_LABEL), "rb"))
assert agent["StartCalendarInterval"] == {"Hour": 20, "Minute": 30} and agent["ProgramArguments"][-1] == "--remind"
assert m.mac_apply_reminder(False, "20:30")[0] and not os.path.exists(m.mac_agent_path(m.MAC_REMIND_LABEL))

print("notification:", m.mac_notify("Sottovoce test", "A test notification"))
print("mac ok")

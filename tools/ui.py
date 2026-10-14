"""Tiny adb driver used to exercise the app by hand on the emulator.

Every command is pinned to the emulator serial so a phone plugged into the same
machine is never touched.

    python tools/ui.py shot NAME            save screenshots/NAME.png
    python tools/ui.py tap TEXT|id:TAG      tap the element with that text, description or test tag
    python tools/ui.py type "some text"     type into the focused field
    python tools/ui.py key BACK             send a key event
    python tools/ui.py dump                 list what is on screen
    python tools/ui.py has TEXT             exit 0 if the text is on screen

Several commands can be chained with "--":  tap id:fab_add -- type 12.5 -- shot add
"""
import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

SERIAL = os.environ.get("TL_SERIAL", "emulator-5554")
ADB = os.path.join(os.environ.get("LOCALAPPDATA", ""), "Android", "Sdk", "platform-tools", "adb.exe")
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def adb(*args, binary=False):
    out = subprocess.run([ADB, "-s", SERIAL, *args], capture_output=True)
    return out.stdout if binary else out.stdout.decode("utf-8", "replace")


def nodes():
    for _ in range(4):
        raw = adb("exec-out", "uiautomator", "dump", "/dev/tty")
        start, end = raw.find("<?xml"), raw.rfind("</hierarchy>")
        if start >= 0 and end > 0:
            try:
                return list(ET.fromstring(raw[start:end + len("</hierarchy>")]).iter("node"))
            except ET.ParseError:
                pass
        time.sleep(0.5)
    raise SystemExit("could not dump the UI")


def center(node):
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.get("bounds")))
    return (x1 + x2) // 2, (y1 + y2) // 2


def find(target, all_nodes=None):
    all_nodes = all_nodes or nodes()
    index = 0
    m = re.match(r"(.*)#(\d+)$", target)
    if m:
        target, index = m.group(1), int(m.group(2))
    if target.startswith("id:"):
        hits = [n for n in all_nodes if n.get("resource-id") == target[3:]]
    else:
        hits = [n for n in all_nodes if target in (n.get("text"), n.get("content-desc"))]
        if not hits:
            hits = [n for n in all_nodes if target.lower() in (n.get("text") or "").lower()
                    or target.lower() in (n.get("content-desc") or "").lower()]
    return hits[index] if len(hits) > index else None


def run(cmd, args):
    if cmd == "shot":
        path = os.path.join(ROOT, "screenshots", args[0] + ".png")
        os.makedirs(os.path.dirname(path), exist_ok=True)
        time.sleep(0.6)
        with open(path, "wb") as f:
            f.write(adb("exec-out", "screencap", "-p", binary=True))
        print("saved", path)
    elif cmd == "tap":
        node = None
        for _ in range(6):
            node = find(args[0])
            if node is not None:
                break
            time.sleep(0.5)
        if node is None:
            raise SystemExit("not on screen: " + args[0])
        x, y = center(node)
        adb("shell", "input", "tap", str(x), str(y))
        time.sleep(0.7)
    elif cmd == "type":
        text = args[0].replace(" ", "%s")
        text = re.sub(r"([\\'\"&|;<>()$`*?#~])", r"\\\1", text)
        adb("shell", "input", "text", text)
        time.sleep(0.4)
    elif cmd == "key":
        adb("shell", "input", "keyevent", "KEYCODE_" + args[0])
        time.sleep(0.5)
    elif cmd == "swipe":
        adb("shell", "input", "swipe", *args)
        time.sleep(0.6)
    elif cmd == "wait":
        time.sleep(float(args[0]))
    elif cmd == "dump":
        for n in nodes():
            label = n.get("text") or n.get("content-desc")
            rid = n.get("resource-id")
            if label or rid:
                print(f"{n.get('bounds'):26} {rid or '':28} {label!r}")
    elif cmd == "has":
        ok = find(args[0]) is not None
        print(("present: " if ok else "MISSING: ") + args[0])
        if not ok:
            raise SystemExit(1)
    else:
        raise SystemExit("unknown command " + cmd)


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    step = []
    steps = []
    for a in sys.argv[1:]:
        if a == "--":
            steps.append(step)
            step = []
        else:
            step.append(a)
    steps.append(step)
    for s in steps:
        if s:
            run(s[0], s[1:])

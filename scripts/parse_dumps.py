import re
import glob
import os

os.chdir(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

for path in sorted(glob.glob("dump_*.xml"), key=lambda p: os.path.getmtime(p), reverse=True)[:6]:
    with open(path, encoding="utf-8", errors="ignore") as f:
        data = f.read()
    texts = re.findall(r'text="([^"]+)"', data)
    descs = re.findall(r'content-desc="([^"]+)"', data)
    keys = ("accept", "confirm", "ride", "rs", "₹", "inr", "booking", "order")
    interesting = []
    for t in texts + descs:
        low = t.lower()
        if any(k in low for k in keys):
            interesting.append(t)
    # clickable nodes with bounds
    nodes = re.findall(
        r'clickable="true"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"[^>]*?(?:text="([^"]*)")?[^>]*?(?:content-desc="([^"]*)")?',
        data,
    )
    def safe(s):
        return str(s).encode("ascii", "backslashreplace").decode("ascii")

    print("===", path)
    print("interesting:", safe(interesting[:50]))
    print("clickable sample:")
    for x1, y1, x2, y2, text, desc in nodes[:25]:
        label = text or desc or "?"
        if label != "?" or int(y2) - int(y1) > 80:
            print(" ", safe(f"[{x1},{y1}][{x2},{y2}] {label!r}"))
    print()

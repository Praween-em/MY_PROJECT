import re, glob, os, sys
sys.stdout.reconfigure(encoding="utf-8", errors="replace")
os.chdir(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

# Parse all dumps for resource-ids, classes, bottom clickables, any accept-like strings
for path in sorted(glob.glob("dump_*.xml")):
    with open(path, encoding="utf-8", errors="ignore") as f:
        data = f.read()
    # All nodes with useful attrs
    node_re = re.compile(
        r'<node[^>]*'
        r'index="(\d+)"[^>]*'
        r'text="([^"]*)"[^>]*'
        r'resource-id="([^"]*)"[^>]*'
        r'class="([^"]*)"[^>]*'
        r'package="([^"]*)"[^>]*'
        r'content-desc="([^"]*)"[^>]*'
        r'[^>]*clickable="([^"]*)"[^>]*'
        r'[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"',
        re.I,
    )
    # looser bounds extract for all nodes
    loose = re.findall(
        r'resource-id="([^"]*)"[^>]*class="([^"]*)"[^>]*package="([^"]*)"[^>]*content-desc="([^"]*)"[^>]*checkable="[^"]*"[^>]*checked="[^"]*"[^>]*clickable="([^"]*)"[^>]*[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"[^>]*text="([^"]*)"',
        data,
    )
    # even looser: any text/desc/id near accept
    texts = set(re.findall(r'text="([^"]+)"', data))
    ids = set(re.findall(r'resource-id="([^"]+)"', data))
    descs = set(re.findall(r'content-desc="([^"]+)"', data))
    classes = set(re.findall(r'class="([^"]+)"', data))

    interesting_ids = [i for i in ids if i]
    bottom_clickables = []
    for m in re.finditer(r'clickable="true"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', data):
        x1,y1,x2,y2 = map(int, m.groups())
        if y1 > 1600:
            # get nearby text from surrounding 200 chars before
            start = max(0, m.start()-400)
            chunk = data[start:m.end()+50]
            tid = re.search(r'resource-id="([^"]*)"', chunk)
            ttxt = re.search(r'text="([^"]*)"', chunk)
            tdesc = re.search(r'content-desc="([^"]*)"', chunk)
            bottom_clickables.append((x1,y1,x2,y2, tid.group(1) if tid else "", ttxt.group(1) if ttxt else "", tdesc.group(1) if tdesc else ""))

    if interesting_ids or any("accept" in t.lower() for t in texts|descs):
        print("FILE", path)
        print("  ids:", sorted(interesting_ids)[:40])
        print("  texts:", [t for t in texts if t][:30])
        print("  descs:", [t for t in descs if t][:30])
        print("  bottom clickables:", bottom_clickables[:15])
        print()

print("--- summary unique resource-ids across dumps ---")
all_ids=set()
for path in glob.glob("dump_*.xml"):
    with open(path,encoding="utf-8",errors="ignore") as f:
        all_ids |= set(re.findall(r'resource-id="([^"]+)"', f.read()))
for i in sorted(x for x in all_ids if x):
    print(i)

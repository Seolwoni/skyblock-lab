"""Writes docs/FEATURES.md from the Feature descriptions in the source (run from the repo root)."""
import re, pathlib

ROOT = pathlib.Path("src/main/kotlin/dev/marceline/features/impl")
TABS = [("general", "General", None), ("dungeon", "Dungeons", "Core"), ("floor7", "Dungeons", "Floor 7"),
        ("routes", "Dungeons", "Routes"), ("fishing", "Fishing", None), ("mining", "Mining", None),
        ("farming", "Farming", None), ("visual", "Visual", None), ("misc", "Misc", None), ("dev", "Dev", None)]
HEAD = re.compile(r'object\s+(\w+)\s*:\s*Feature\(')


def call_args(text, start):
    """The text between Feature( and its matching ), skipping parentheses inside strings."""
    depth, i, in_str = 1, start, False
    while i < len(text) and depth:
        c = text[i]
        if in_str:
            if c == "\\": i += 1
            elif c == '"': in_str = False
        elif c == '"': in_str = True
        elif c == "(": depth += 1
        elif c == ")": depth -= 1
        i += 1
    return text[start:i - 1]


class M:
    def __init__(self, m, args): self.m, self.args = m, args
    def group(self, n): return self.m.group(1) if n == 1 else self.args
    def start(self): return self.m.start()


class DECL:
    @staticmethod
    def finditer(text):
        for m in HEAD.finditer(text):
            yield M(m, call_args(text, m.end()))

def split_name(n):
    return re.sub(r'(?<=[a-z0-9])(?=[A-Z])', ' ', n)

# descriptions kept apart for features declared without one (FeatureDescriptions.kt)
FALLBACK = dict(re.findall(r'"(\w+)"\s+to\s+"((?:[^"\\]|\\.)*)"', pathlib.Path("src/main/kotlin/dev/marceline/features/FeatureDescriptions.kt").read_text(encoding="utf-8")))

rows = {}
for f in sorted(ROOT.rglob("*.kt")):
    text = f.read_text(encoding="utf-8")
    pkg = f.relative_to(ROOT).parts[0]
    for m in DECL.finditer(text):
        args = m.group(2)
        strings = re.findall(r'"((?:[^"\\]|\\.)*)"', args)
        if '+' in args and len(strings) > 1 and not re.search(r'name\s*=', args.split('+')[0]):
            strings = ["".join(strings)]
        name = re.search(r'name\s*=\s*"([^"]*)"', args)
        desc = next((s for s in strings if not name or s != name.group(1)), "") or FALLBACK.get(m.group(1), "")
        full_only = text[:m.start()].count("//#if CHEAT") > text[:m.start()].count("//#endif")
        rows.setdefault(pkg, []).append((name.group(1) if name else split_name(m.group(1)), desc.replace("\\n", " "), full_only))

out = ["# Marceline features", "",
       "Every feature in the settings window (`/mar`), by tab. Generated from the code by `tools/gen_features.py`;",
       "run it again after adding or describing a feature. *Full build* marks automation that only exists in the",
       "full (non-legit) build.", ""]
last_tab = None
for pkg, tab, group in TABS:
    items = sorted(rows.get(pkg, []), key=lambda r: r[0].lower())
    if not items:
        continue
    if tab != last_tab:
        out += [f"## {tab}", ""]
        last_tab = tab
    if group:
        out += [f"### {group}", ""]
    out += ["| Feature | What it does |", "|---|---|"]
    for name, desc, full in items:
        d = (desc or "-").replace("|", "\\|")
        out.append(f"| {name}{' *(full build)*' if full else ''} | {d} |")
    out.append("")
pathlib.Path("docs/FEATURES.md").write_text("\n".join(out), encoding="utf-8")
print(sum(len(v) for v in rows.values()), "features")

#!/usr/bin/env python3
"""Writes the settings layout (pages, groups and option keys) of PrusaSlicer's FFF tabs.

The layout is read from PrusaSlicer's src/slic3r/GUI/Tab.cpp, so the app shows the
options on the same pages and in the same groups as the desktop application.

Usage: scripts/gen-settings-layout.py [Tab.cpp] [output.json]
"""
import json
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TAB_CPP = os.path.join(ROOT, "third_party/PrusaSlicer/src/slic3r/GUI/Tab.cpp")
OUTPUT = os.path.join(ROOT, "app/src/main/assets/settings_layout.json")

# Functions that build each tab, in page order.
TABS = {
    "print": ["void TabPrint::build()"],
    "filament": ["void TabFilament::build()"],
    "printer": [
        "void TabPrinter::build_fff()",
        "PageShp TabPrinter::build_kinematics_page()",
        "void TabPrinter::build_extruder_pages(",
        "void TabPrinter::build_unregular_pages(",
    ],
}

# Pages PrusaSlicer keeps at the end of a tab.
LAST_PAGES = ["Notes", "Dependencies"]

AXES = ["x", "y", "z", "e"]
KEY_FIRST_ARG = re.compile(r'(?:append_single_option_line|get_option|create_single_option_line)\("([a-z0-9_]+)"')
KEY_SECOND_ARG = re.compile(
    r'(?:append_option_line|create_line_with_widget|create_line_with_near_label_widget)\([^,()]+(?:\(\))?,\s*"([a-z0-9_]+)"(\s*\+\s*axis)?')
PAGE = re.compile(r'add_options_page\(L\("([^"]+)"\)')
GROUP = re.compile(r'new_optgroup\((?:L\()?"([^"]*)"')


def function_body(lines, signature):
    for i, line in enumerate(lines):
        if line.startswith(signature):
            body = []
            for line in lines[i + 1:]:
                if line == "}":
                    return body
                body.append(line)
    raise SystemExit(f"{signature} not found in Tab.cpp")


def filament_overrides(source):
    block = source.split("filament_overrides_option_keys {", 1)[1].split("};", 1)[0]
    groups = []
    for title, keys in re.findall(r'\{"([^"]+)",\s*\{([^}]*)\}', block):
        groups.append({"title": title, "keys": re.findall(r'"([a-z0-9_]+)"', keys)})
    return {"title": "Filament Overrides", "groups": groups}


def build_tab(lines, signatures, overrides):
    pages = []
    seen = set()

    def group():
        if not pages:
            pages.append({"title": "General", "groups": []})
        if not pages[-1]["groups"]:
            pages[-1]["groups"].append({"title": "", "keys": []})
        return pages[-1]["groups"][-1]

    def add(key):
        if key not in seen:
            seen.add(key)
            group()["keys"].append(key)

    for signature in signatures:
        for line in function_body(lines, signature):
            code = line.split("//", 1)[0]
            if "add_filament_overrides_page()" in code:
                pages.append(overrides)
                continue
            m = PAGE.search(code)
            if m or "add_options_page(page_name" in code:
                pages.append({"title": m.group(1) if m else "Extruder", "groups": []})
                continue
            m = GROUP.search(code)
            if m:
                pages[-1]["groups"].append({"title": m.group(1), "keys": []})
                continue
            for key in KEY_FIRST_ARG.findall(code):
                add(key)
            for key, per_axis in KEY_SECOND_ARG.findall(code):
                for k in ([key + a for a in AXES] if per_axis else [key]):
                    add(k)

    # Drop groups and pages that ended up without options (description lines, buttons).
    for page in pages:
        page["groups"] = [g for g in page["groups"] if g["keys"]]
    pages = [p for p in pages if p["groups"]]
    return sorted(pages, key=lambda p: LAST_PAGES.index(p["title"]) + 1 if p["title"] in LAST_PAGES else 0)


def main():
    tab_cpp = sys.argv[1] if len(sys.argv) > 1 else TAB_CPP
    output = sys.argv[2] if len(sys.argv) > 2 else OUTPUT
    source = open(tab_cpp, encoding="utf-8").read()
    lines = source.splitlines()
    overrides = filament_overrides(source)
    layout = {tab: build_tab(lines, sigs, overrides) for tab, sigs in TABS.items()}
    with open(output, "w", encoding="utf-8") as f:
        json.dump(layout, f, indent=1)
        f.write("\n")
    for tab, pages in layout.items():
        print(f"{tab}: {len(pages)} pages, {sum(len(g['keys']) for p in pages for g in p['groups'])} options")


if __name__ == "__main__":
    main()

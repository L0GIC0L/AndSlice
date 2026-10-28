#!/usr/bin/env python3
"""Copies PrusaSlicer's FFF vendor profiles into the app and writes their catalog.

The vendor bundles go to app/src/main/assets/profiles/vendors/. The catalog
(profiles/catalog.json) lists every vendor with its printer models and nozzle
variants, so the app can offer them without loading the bundles. AndSlice's own
bundles (Kobra 3, custom printer) come first.

Usage: scripts/update-vendor-profiles.py [PrusaSlicer resources/profiles dir]
"""
import json
import os
import re
import shutil
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SOURCE = os.path.join(ROOT, "third_party/PrusaSlicer/resources/profiles")
ASSETS = os.path.join(ROOT, "app/src/main/assets/profiles")
OWN_BUNDLES = ["AnycubicKobra3.ini"]  # listed first; CustomPrinter.ini is the base of custom printers


def read_sections(path):
    """Minimal reader for the [vendor] and [printer_model:*] sections of a config bundle."""
    sections = {}
    current = None
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith("#") or line.startswith(";"):
                continue
            m = re.match(r"^\[(.+)\]$", line)
            if m:
                current = m.group(1)
                sections.setdefault(current, {})
                continue
            if current and "=" in line:
                key, value = line.split("=", 1)
                sections[current][key.strip()] = value.strip()
    return sections


def vendor_entry(path, file_name):
    sections = read_sections(path)
    vendor = sections.get("vendor", {})
    models = []
    for name, values in sections.items():
        if not name.startswith("printer_model:"):
            continue
        if values.get("technology", "FFF") != "FFF":
            continue
        variants = [v.strip() for v in values.get("variants", "").split(";") if v.strip()]
        if not variants:
            continue
        models.append({
            "id": name.split(":", 1)[1],
            "name": values.get("name", name.split(":", 1)[1]),
            "family": values.get("family", ""),
            "variants": variants,
        })
    return {
        "id": os.path.splitext(os.path.basename(file_name))[0],
        "name": vendor.get("name", os.path.splitext(file_name)[0]),
        "file": file_name,
        "templates": vendor.get("templates_profile", "0") == "1",
        "models": models,
    }


def main():
    source = sys.argv[1] if len(sys.argv) > 1 else SOURCE
    vendors_dir = os.path.join(ASSETS, "vendors")
    shutil.rmtree(vendors_dir, ignore_errors=True)
    os.makedirs(vendors_dir)

    catalog = [vendor_entry(os.path.join(ASSETS, f), f) for f in OWN_BUNDLES]
    for file_name in sorted(os.listdir(source), key=str.lower):
        if not file_name.endswith(".ini"):
            continue
        entry = vendor_entry(os.path.join(source, file_name), "vendors/" + file_name)
        # SLA-only bundles have no FFF models; the templates bundle has none at all.
        if not entry["models"] and not entry["templates"]:
            continue
        shutil.copyfile(os.path.join(source, file_name), os.path.join(vendors_dir, file_name))
        catalog.append(entry)

    with open(os.path.join(ASSETS, "catalog.json"), "w", encoding="utf-8") as f:
        json.dump(catalog, f, indent=1)
        f.write("\n")
    printers = sum(len(v["models"]) for v in catalog)
    print(f"{len(catalog)} bundles, {printers} printer models")


if __name__ == "__main__":
    main()

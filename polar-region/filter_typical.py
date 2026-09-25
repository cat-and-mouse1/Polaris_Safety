#!/usr/bin/env python3
"""
从 Polar Point 筛选典型样本（不限数量）
策略：保留所有有真实家族名 + 所有恶意包名
"""
import json
import os
import random

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
POINT_PATH = os.path.join(SCRIPT_DIR, "..", "polar-point", "iodb.json")
OUTPUT_PATH = os.path.join(SCRIPT_DIR, "iodb.json")

LOCAL_HASH = "Local Hash Database"
LOCAL_PKG = "Local Package Database"
EXCLUDE_FAMILIES = {"Blocklist.de", "Emerging Threats", "Feodo Tracker"}

print("Reading Polar Point ...")
with open(POINT_PATH, "r", encoding="utf-8") as f:
    point = json.load(f)

entries = point["entries"]
print(f"Total: {len(entries)}")

# 分三组
android_malware = []   # 有真实家族名（排除恶意IP）
hash_entries = []      # SHA-256 哈希（Local Hash Database）
pkg_entries = []       # 包名（Local Package Database）

for e in entries:
    family = e.get("family", "")
    has_pkg = bool(e.get("pkg", ""))
    has_sha = bool(e.get("sha256", ""))

    if family in (LOCAL_HASH, LOCAL_PKG, ""):
        if family == LOCAL_HASH and has_sha:
            hash_entries.append(e)
        elif family == LOCAL_PKG and has_pkg:
            pkg_entries.append(e)
    elif family not in EXCLUDE_FAMILIES:
        android_malware.append(e)

print(f"Android malware families: {len(android_malware)}")
print(f"SHA-256 hashes: {len(hash_entries)}")
print(f"Package names: {len(pkg_entries)}")

# 全部保留
selected = []
selected.extend(android_malware)
selected.extend(hash_entries)
selected.extend(pkg_entries)

print(f"\nTotal selected: {len(selected)}")

# 统计
families = {}
types = {}
severities = {}
for e in selected:
    families[e.get("family", "")] = families.get(e.get("family", ""), 0) + 1
    types[e.get("type", "")] = types.get(e.get("type", ""), 0) + 1
    severities[e.get("severity", "")] = severities.get(e.get("severity", ""), 0) + 1

output = {
    "db_version": 14,
    "updated_at": "2026-09-12",
    "source": "Polar Point v1 typical samples (no limit)",
    "note": "All named families + all SHA-256 hashes + all package names. Full DB: Polar Point.",
    "entries": selected
}

with open(OUTPUT_PATH, "w", encoding="utf-8") as f:
    json.dump(output, f, ensure_ascii=False, separators=(",", ":"))

print(f"\n[Polar Region v14] Generated: {len(selected)} entries")
print(f"  Families: {len(families)}")
print(f"\nBy type:")
for k, v in sorted(types.items(), key=lambda x: -x[1]):
    print(f"  {k}: {v}")
print(f"\nBy severity:")
for k, v in sorted(severities.items(), key=lambda x: -x[1]):
    print(f"  {k}: {v}")

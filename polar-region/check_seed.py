#!/usr/bin/env python3
import json
import os

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
SEED_PATH = os.path.join(SCRIPT_DIR, "..", "app", "src", "main", "assets", "iodb_seed.json")

with open(SEED_PATH, "r", encoding="utf-8") as f:
    seed = json.load(f)

print(f"种子库条目数: {len(seed['entries'])}")

# 统计各家族数量
families = {}
types = {}
severities = {}
for e in seed["entries"]:
    fam = e.get("family", "unknown")
    t = e.get("type", "unknown")
    s = e.get("severity", "unknown")
    families[fam] = families.get(fam, 0) + 1
    types[t] = types.get(t, 0) + 1
    severities[s] = severities.get(s, 0) + 1

print("\n按家族:")
for k, v in sorted(families.items(), key=lambda x: -x[1]):
    print(f"  {k}: {v}")

print("\n按类型:")
for k, v in sorted(types.items(), key=lambda x: -x[1]):
    print(f"  {k}: {v}")

print("\n按严重度:")
for k, v in sorted(severities.items(), key=lambda x: -x[1]):
    print(f"  {k}: {v}")

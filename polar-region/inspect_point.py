#!/usr/bin/env python3
import json

with open('E:/Personal/操作/AI/Output/Polaris_GitHub_v2.1.0/polaris-point/iodb.json', 'r', encoding='utf-8') as f:
    d = json.load(f)

entries = d['entries']
print(f"Total: {len(entries)}")
print(f"Keys in first entry: {list(entries[0].keys())}")

# 看前3条
for i in range(min(3, len(entries))):
    print(f"\n--- Entry {i} ---")
    print(json.dumps(entries[i], ensure_ascii=False, indent=2))

# 看有 pkg 的条目
with_pkg = [e for e in entries if e.get('pkg')]
print(f"\nEntries with pkg: {len(with_pkg)}")
if with_pkg:
    print("First 3 with pkg:")
    for e in with_pkg[:3]:
        print(json.dumps(e, ensure_ascii=False, indent=2))

# 看非 Local Hash Database 的
named = [e for e in entries if e.get('family', '') not in ('Local Hash Database', 'Local Package Database', '')]
print(f"\nNamed family entries: {len(named)}")
if named:
    print("First 3 named:")
    for e in named[:3]:
        print(json.dumps(e, ensure_ascii=False, indent=2))

# 看有多少种 family
families = set(e.get('family', '') for e in entries)
print(f"\nUnique families: {len(families)}")
print("Families:", sorted(families)[:20])

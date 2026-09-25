#!/usr/bin/env python3
import json

d = json.load(open('E:/Personal/操作/AI/Output/Polaris_GitHub_v2.1.0/polar-region/iodb.json', 'r', encoding='utf-8'))
print(f"version: {d['db_version']}")
print(f"entries: {len(d['entries'])}")

families = {}
types = {}
severities = {}
for e in d['entries']:
    families[e.get('family', '')] = families.get(e.get('family', ''), 0) + 1
    types[e.get('type', '')] = types.get(e.get('type', ''), 0) + 1
    severities[e.get('severity', '')] = severities.get(e.get('severity', ''), 0) + 1

print(f"families: {len(families)}")
print("\nBy type:")
for k, v in sorted(types.items(), key=lambda x: -x[1]):
    print(f"  {k}: {v}")
print("\nBy severity:")
for k, v in sorted(severities.items(), key=lambda x: -x[1]):
    print(f"  {k}: {v}")
print(f"\nAll families ({len(families)}):")
for k, v in sorted(families.items(), key=lambda x: -x[1]):
    print(f"  {k}: {v}")

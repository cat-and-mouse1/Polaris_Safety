#!/usr/bin/env python3
import json
d = json.load(open('E:/Personal/操作/AI/Output/Polaris_GitHub_v2.1.0/polar-region/iodb.json', 'r', encoding='utf-8'))
print(f"version: {d['db_version']}")
print(f"entries: {len(d['entries'])}")
print(f"source: {d['source']}")

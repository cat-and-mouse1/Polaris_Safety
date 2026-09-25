#!/usr/bin/env python3
"""
从种子库提取典型样本，生成精简版 Polar Region v14
"""
import json
import os

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
SEED_PATH = os.path.join(SCRIPT_DIR, "..", "app", "src", "main", "assets", "iodb_seed.json")
OUTPUT_PATH = os.path.join(SCRIPT_DIR, "iodb.json")

with open(SEED_PATH, "r", encoding="utf-8") as f:
    seed = json.load(f)

output = {
    "db_version": 14,
    "updated_at": "2026-09-12",
    "source": "Polar Region 典型样本精选 (from iodb_seed)",
    "note": "精简版：仅含高置信度典型恶意样本，用于快速本地匹配。全量库请使用 Polaris Point。",
    "entries": seed["entries"]
}

with open(OUTPUT_PATH, "w", encoding="utf-8") as f:
    json.dump(output, f, ensure_ascii=False, separators=(",", ":"))

print(f"Polar Region v14 已生成: {len(seed['entries'])} 条典型样本")
print(f"输出: {OUTPUT_PATH}")

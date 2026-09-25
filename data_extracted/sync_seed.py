#!/usr/bin/env python3
"""同步种子/校验数据完整性 (占位: 统计各文件行数)."""
import os, json
BASE = os.path.dirname(os.path.abspath(__file__))
for root, _, files in os.walk(os.path.join(BASE, "data")):
    for fn in sorted(files):
        fp = os.path.join(root, fn)
        print(os.path.relpath(fp, BASE), os.path.getsize(fp))

#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""统计 iodb.json 的内容构成（家族/类型/等级/哈希类型）。

用法：
    python tools/ioc_stats.py polar-region/iodb.json polar-point/iodb.json
"""
import collections
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from ioc_pack import iter_entries, read_head  # noqa: E402


def stats(path):
    version, updated, source = read_head(path)
    fam = collections.Counter()
    typ = collections.Counter()
    sev = collections.Counter()
    hashes = collections.Counter()
    n = 0
    for e in iter_entries(path):
        n += 1
        fam[e.get("family") or "(empty)"] += 1
        typ[e.get("type") or "(empty)"] += 1
        sev[e.get("severity") or "(empty)"] += 1
        sha = (e.get("sha256") or "")
        md5 = (e.get("md5") or "")
        sha1 = (e.get("sha1") or "")
        if len(sha) == 64:
            hashes["sha256"] += 1
        elif len(md5) == 32:
            hashes["md5"] += 1
        elif len(sha1) == 40:
            hashes["sha1"] += 1
        elif e.get("pkg"):
            hashes["无哈希(纯包名)"] += 1
        else:
            hashes["未知"] += 1

    print(f"\n===== {path} =====")
    print(f"db_version = {version}   updated_at = {updated!r}")
    print(f"source     = {source!r}")
    print(f"总条数     = {n}")
    print(f"哈希类型   = {dict(hashes)}")
    print(f"type 分布  = {dict(typ)}")
    print(f"等级分布   = {dict(sev)}")
    print(f"家族种类数 = {len(fam)}")
    print("家族 Top 15:")
    for k, v in fam.most_common(15):
        print(f"  {v:>9}  {k}")


if __name__ == "__main__":
    for p in (sys.argv[1:] or ["polar-region/iodb.json", "polar-point/iodb.json"]):
        if os.path.exists(p):
            stats(p)
        else:
            print(f"缺失: {p}")

#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把 MH-100K 数据集的 Android 恶意样本（包名 + SHA-256）合入 iodb.json。

背景：
    现有 region 库的 5 万条包名来自 PyPI/NPM（形如 `0wneg`），客户端按
    `a.b.c` 形态提取文件名 token，命中率几乎为 0（全库仅 98 条可匹配）。
    MH-100K（CC BY 4.0，10.19 万样本）提供真实 Android 包名 + SHA-256 + VT 检出数，
    可直接补齐这一块。

数据来源：
    https://github.com/Malware-Hunter/MH-100K-dataset
    data/processed/mh100_labels.csv  （字段：SHA256, NOME, PACOTE, vt_detection, CLASS）

用法：
    python tools/import_mh100k.py polar-region/iodb.json <mh100_labels.csv> [输出文件]
"""
import csv
import datetime
import json
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from ioc_pack import iter_entries, read_head  # noqa: E402

FAMILY = "MH-100K"
# 与客户端 FileScanner.extractPkgToken 完全一致的形态：至少两段点分，段内仅小写字母/数字/下划线
PKG_PAT = re.compile(r"[a-z0-9_]+(\.[a-z0-9_]+){2,}")


def severity_of(vt):
    if vt >= 5:
        return "high"
    if vt >= 2:
        return "medium"
    return "low"


def main():
    if len(sys.argv) < 3:
        print(__doc__)
        return 2
    src, csv_path = sys.argv[1], sys.argv[2]
    dst = sys.argv[3] if len(sys.argv) > 3 else src + ".new"

    version, updated, source = read_head(src)
    print(f"源库 {src}: db_version={version} updated_at={updated!r}")

    # 1) 收集现有索引，用于去重
    seen_sha, seen_pkg = set(), set()
    n_old = 0
    for e in iter_entries(src):
        n_old += 1
        s = (e.get("sha256") or "")
        if len(s) == 64:
            seen_sha.add(s.lower())
        p = (e.get("pkg") or "")
        if p:
            seen_pkg.add(p.lower())
    print(f"  现有 {n_old} 条（sha256 {len(seen_sha)} / pkg {len(seen_pkg)}）")

    # 2) 从 CSV 挑选新增条目
    csv.field_size_limit(10 ** 7)
    new_entries = []
    skipped = {"dup_sha": 0, "dup_pkg": 0, "no_pkg": 0, "vt_le_0": 0}
    vt_hist = {}
    with open(csv_path, encoding="utf-8", errors="replace") as f:
        for row in csv.DictReader(f):
            sha = (row.get("SHA256") or "").strip().lower()
            pkg = (row.get("PACOTE") or "").strip()
            if len(sha) != 64:
                continue
            try:
                vt = int((row.get("vt_detection") or "0").strip() or 0)
            except ValueError:
                vt = 0
            if vt <= 0:
                skipped["vt_le_0"] += 1
                continue
            if not pkg or not PKG_PAT.fullmatch(pkg.lower()):
                skipped["no_pkg"] += 1
                continue
            if sha in seen_sha:
                skipped["dup_sha"] += 1
                continue
            if pkg.lower() in seen_pkg:
                skipped["dup_pkg"] += 1
                continue
            seen_sha.add(sha)
            seen_pkg.add(pkg.lower())
            sev = severity_of(vt)
            vt_hist[sev] = vt_hist.get(sev, 0) + 1
            new_entries.append({
                "pkg": pkg,
                "sha256": sha,
                "family": FAMILY,
                "type": "malware",
                "severity": sev,
                "desc": f"Android malware sample with AV detections (VT {vt})",
                "tags": ["android", "mh100k", "av-confirmed"],
                "url": "",
            })
    print(f"  新增 {len(new_entries)} 条；等级分布 {vt_hist}")
    print(f"  跳过 {skipped}")

    # 3) 流式写出：原条目 + 新条目
    head = {
        "db_version": version + 1,
        "updated_at": datetime.date.today().isoformat(),
        "source": (source or "Polar Region aggregated") + " + MH-100K Android samples",
    }
    tmp = dst + ".tmp"
    with open(tmp, "w", encoding="utf-8") as out:
        out.write("{")
        out.write(",".join(f'"{k}":{json.dumps(v, ensure_ascii=False)}' for k, v in head.items()))
        out.write(',"entries":[')
        first = True
        for e in iter_entries(src):
            if not first:
                out.write(",")
            out.write(json.dumps(e, ensure_ascii=False, separators=(",", ":")))
            first = False
        for e in new_entries:
            out.write(",")
            out.write(json.dumps(e, ensure_ascii=False, separators=(",", ":")))
        out.write("]}")
    os.replace(tmp, dst)

    print(f"合并完成: {n_old} + {len(new_entries)} = {n_old + len(new_entries)} 条")
    print(f"输出 {dst}  {os.path.getsize(dst) / 1024 / 1024:.1f} MB  db_version={version + 1}")
    return 0


if __name__ == "__main__":
    sys.exit(main())

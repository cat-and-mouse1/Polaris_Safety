#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""从 region 的 iodb.json 生成 point（内置精选）库。

规则与历史 _build_point.py 一致：排除 family 为 GENERIC 的条目
（`Local Hash Database` 是 153 万条纯哈希，属于 region 专有能力，不进点库），
其余具名威胁全部保留，包括新增的 MH-100K Android 样本。

相比旧脚本的改进：不依赖 ijson，改用 tools/ioc_pack.iter_entries 的流式解析器。

用法：
    python tools/build_point.py [polar-region/iodb.json] [polar-point/iodb.json]
"""
import datetime
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from ioc_pack import iter_entries, read_head  # noqa: E402

GENERIC = {"", "Local Hash Database", "Unknown", "unknown"}
SIZE_CAP = 95_000_000
POINT_DB_VERSION = 2


def main():
    src = sys.argv[1] if len(sys.argv) > 1 else "polar-region/iodb.json"
    dst = sys.argv[2] if len(sys.argv) > 2 else "polar-point/iodb.json"

    _, updated, source = read_head(src)
    print(f"源库 {src}  updated_at={updated!r}")

    tmp = dst + ".tmp"
    kept = 0
    size = 0
    fam = {}
    with open(tmp, "w", encoding="utf-8") as out:
        head = {
            "db_version": POINT_DB_VERSION,
            "updated_at": datetime.date.today().isoformat(),
            "source": "Polar Region filtered to named-family threats (excl. Local Hash Database)",
        }
        prefix = "{" + ",".join(
            f'"{k}":{json.dumps(v, ensure_ascii=False)}' for k, v in head.items()) + ',"entries":['
        out.write(prefix)
        size += len(prefix.encode("utf-8"))
        first = True
        for e in iter_entries(src):
            if (e.get("family") or "") in GENERIC:
                continue
            s = json.dumps(e, ensure_ascii=False, separators=(",", ":"))
            b = s.encode("utf-8")
            if not first:
                out.write(",")
                size += 1
            out.write(s)
            size += len(b)
            first = False
            kept += 1
            f = e.get("family") or "(empty)"
            fam[f] = fam.get(f, 0) + 1
            if size >= SIZE_CAP:
                print(f"  ! 达到体积上限 {SIZE_CAP}，提前停止")
                break
        out.write("]}")
    os.replace(tmp, dst)

    top = sorted(fam.items(), key=lambda kv: -kv[1])[:6]
    print(f"保留 {kept} 条，{os.path.getsize(dst) / 1024 / 1024:.1f} MB，家族 {len(fam)} 种")
    print("  Top:", top)
    print(f"  输出 {dst}  db_version={POINT_DB_VERSION}")
    return 0


if __name__ == "__main__":
    sys.exit(main())

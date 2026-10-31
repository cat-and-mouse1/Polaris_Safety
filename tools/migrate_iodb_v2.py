#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把 iodb.json 的 sha256 字段按长度拆到 md5 / sha1 / sha256 三个字段（C 方案数据侧迁移）。

背景：
    历史管道（build_ioc.py 旧版 load_local_hashes）把 MD5(32)、SHA-1(40)、SHA-256(64)
    统统一塞进 `sha256` 字段。客户端按 SHA-256 查询，导致 region 全量库里
    144 万条形同废数据（占 89.5%）。
    本脚本就地迁移，保留条数与原始顺序，不重跑 build_ioc.py，
    避免数据源变动导致条数漂移。

用法：
    python tools/migrate_iodb_v2.py polar-region/iodb.json            # 输出 xxx.json.v2
    python tools/migrate_iodb_v2.py polar-region/iodb.json --in-place  # 直接覆盖
"""
import argparse
import datetime
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from ioc_pack import iter_entries, read_head  # noqa: E402

ALGO_BY_LEN = {32: "md5", 40: "sha1", 64: "sha256"}


def main():
    ap = argparse.ArgumentParser(description="iodb.json 哈希字段迁移（sha256 -> md5/sha1/sha256）")
    ap.add_argument("src", help="输入 iodb.json")
    ap.add_argument("--in-place", action="store_true", help="原地覆盖（默认输出 .v2）")
    args = ap.parse_args()

    src = args.src
    version, updated, source = read_head(src)
    if not updated:
        # 头部没有 updated_at 时，用源文件修改日期兜底（region 历史数据属这种）
        updated = datetime.date.fromtimestamp(os.path.getmtime(src)).isoformat()
    if not source:
        source = "Polar Region aggregated"

    tmp = src + ".migrate.tmp"
    stats = {"md5": 0, "sha1": 0, "sha256": 0, "empty": 0, "total": 0}

    head = {"db_version": version, "updated_at": updated, "source": source}
    with open(tmp, "w", encoding="utf-8") as out:
        out.write("{")
        out.write(",".join(f'"{k}":{json.dumps(v, ensure_ascii=False)}'
                           for k, v in head.items()))
        out.write(',"entries":[')
        first = True
        for e in iter_entries(src):
            sha = (e.get("sha256") or "").strip().lower()
            e["sha256"] = ""
            e["md5"] = ""
            e["sha1"] = ""
            algo = ALGO_BY_LEN.get(len(sha))
            if algo:
                e[algo] = sha
                stats[algo] += 1
            else:
                stats["empty"] += 1
            stats["total"] += 1
            if not first:
                out.write(",")
            out.write(json.dumps(e, ensure_ascii=False, separators=(",", ":")))
            first = False
        out.write("]}")

    dst = src if args.in_place else src + ".v2"
    os.replace(tmp, dst)
    size = os.path.getsize(dst)
    print(f"输出: {dst}  {size / 1024 / 1024:.1f} MB")
    print(f"  条数 {stats['total']}：md5={stats['md5']} sha1={stats['sha1']} "
          f"sha256={stats['sha256']} 空={stats['empty']}")
    print(f"  db_version={version} updated_at={updated!r}")
    return 0


if __name__ == "__main__":
    sys.exit(main())

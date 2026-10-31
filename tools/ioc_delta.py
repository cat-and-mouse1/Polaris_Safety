#!/usr/bin/env python3
"""
Polaris 病毒库增量包生成器：old.json → new.json 之间「新增」的条目，打包成 iodb-delta.bin。

设计取舍
--------
* 只做「新增」，不做「修改/删除」。条目身份由 (pkg, sha256, md5, sha1) 决定，
  所以已存在条目的 family/severity 文字变化不会进增量包——这类变更靠周期性全量更新覆盖。
* 增量包本身就是一个合法的 v2 bin（同样的三哈希表 + 包名/URL 索引 + 明细 + 字符串池），
  客户端用同一套 IocBinaryIndex 打开，只是 header 偏移 88 记录了「基线版本」，
  用来判断能否叠加到本地那份全量库上。
* 之所以不用「文件分块 diff」：库按哈希排序，插入任意新条目会让其后所有字节位移，
  固定块比对几乎全 block 失效；而按条目做差集，体积只与新增量相关。

用法
----
    tools/ioc_delta.py old.json new.json polar-region/iodb-delta-<oldVer>.bin

文件名建议 `iodb-delta-<基线版本>.bin`：客户端会按「本地版本号」拼出该地址去探测。
"""

import argparse
import hashlib
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from ioc_pack import iter_entries, compile_db, read_head  # noqa: E402


def entry_key(e):
    """条目身份：包名 + 三种哈希。8 字节摘要足够（碰撞概率 ~2^-64，且错了只会多下一条）。"""
    raw = "\x1f".join((
        e.get("pkg") or "",
        (e.get("sha256") or "").lower(),
        (e.get("md5") or "").lower(),
        (e.get("sha1") or "").lower(),
    ))
    return hashlib.blake2b(raw.encode("utf-8"), digest_size=8).digest()


def main():
    ap = argparse.ArgumentParser(description="生成 Polaris 病毒库增量包")
    ap.add_argument("old", help="基线 iodb.json（本地已有的那一版）")
    ap.add_argument("new", help="目标 iodb.json（最新一版）")
    ap.add_argument("dst", help="输出 iodb-delta-<baseVer>.bin")
    args = ap.parse_args()

    old_ver, _, _ = read_head(args.old)
    new_ver, new_updated, new_source = read_head(args.new)
    if new_ver <= old_ver:
        print(f"目标版本 {new_ver} 不高于基线 {old_ver}，无需增量包", file=sys.stderr)
        return 2

    print(f"基线 {args.old} v{old_ver}")
    print(f"目标 {args.new} v{new_ver}")

    # 1) 基线条目指纹
    old_keys = set()
    n_old = 0
    for e in iter_entries(args.old):
        old_keys.add(entry_key(e))
        n_old += 1
        if n_old % 500000 == 0:
            print(f"  基线已扫描 {n_old} 条…")
    print(f"  基线 {n_old} 条，唯一指纹 {len(old_keys)}")

    # 2) 目标里筛出新增（同一目标内也去重）
    new_keys = set()
    added = []
    n_new = 0
    for e in iter_entries(args.new):
        n_new += 1
        k = entry_key(e)
        if k in old_keys or k in new_keys:
            continue
        new_keys.add(k)
        added.append(e)
        if len(added) % 20000 == 0:
            print(f"  新增 {len(added)} 条…")
    print(f"  目标 {n_new} 条，新增 {len(added)} 条")

    if not added:
        print("没有新增条目，不生成增量包", file=sys.stderr)
        return 2

    # 3) 写成临时 JSON，交给 ioc_pack 编译（复用同一套布局代码，避免格式漂移）
    tmp = args.dst + ".tmp.json"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump({
            "db_version": new_ver,
            "updated_at": new_updated,
            "source": (new_source or "") + " (delta)",
            "entries": added,
        }, f, ensure_ascii=False)
    try:
        rc = compile_db(tmp, args.dst, 0, old_ver)
    finally:
        try:
            os.remove(tmp)
        except OSError:
            pass

    delta_size = os.path.getsize(args.dst)
    full_size = os.path.getsize(args.new)
    print(f"\n增量包 {args.dst}")
    print(f"  {delta_size/1024/1024:.2f} MB / 全量 JSON {full_size/1024/1024:.1f} MB"
          f"  → {delta_size*100/full_size:.1f}%")
    print(f"  基线 v{old_ver} → 目标 v{new_ver}，新增 {len(added)} 条")
    print(f"  客户端地址约定：iodb-delta-{old_ver}.bin（放在 iodb.bin 同目录）")
    return rc


if __name__ == "__main__":
    sys.exit(main())

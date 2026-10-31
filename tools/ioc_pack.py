#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
iodb.json → iodb.bin 编译器（Polaris 威胁情报二进制索引）

为什么需要：
    region 全量库是 161 万条、357 MB 的 JSON。若按「解析成对象 + HashMap」加载，
    常驻内存约 558 MB（实测估算），任何手机都装不下；即使流式解析也会 OOM。
    本脚本把它编译成「排序哈希表 + 定长明细 + 字符串字典」的二进制文件：
      - 客户端用 MappedByteBuffer 只读映射，堆内存占用 ≈ 0
      - sha256 / 包名 / URL 三条索引均为二分查找，O(log n)
      - 体积约 120 MB（对 357 MB 的 JSON），且可分版本做增量

二进制布局（小端，格式 v2）：
    HEADER 128B
       0    magic       char[8]  "POLARISB"
       8    formatVer   u32      2
       12   dbVersion   u32
       16   updatedAtId u32      字符串字典 id（0xFFFFFFFF = 无）
       20   sourceId    u32
       24   entryCount  u32
       28   sha256Count u32
       32   pkgCount    u32
       36   urlCount    u32
       40   strCount    u32
       44   sha256Off   u32      以下均为文件内绝对偏移
       48   pkgOff      u32
       52   urlOff      u32
       56   detailOff   u32
       60   strIndexOff u32
       64   strPoolOff  u32
       68   strPoolSize u32
       72   md5Count    u32      v2 新增
       76   md5Off      u32      v2 新增
       80   sha1Count   u32      v2 新增
       84   sha1Off     u32      v2 新增
       88   reserved[10]
    SHA256 36B × n   sha256[32] + u32 detailIndex      （按 32 字节升序）
    MD5    20B × n   md5[16]    + u32 detailIndex      （按 16 字节升序）
    SHA1   24B × n   sha1[20]   + u32 detailIndex      （按 20 字节升序）
    PKG    12B × n   u64 fnv1a64(pkg) + u32 detailIndex（按 hash 升序）
    URL    12B × n   u64 fnv1a64(lower(url)) + u32 detailIndex
    DETAIL 24B × n   u32 pkgId, familyId, typeId, sevId, descId, urlId
    STRIDX 4B × m    u32 → 字符串池偏移
    STRPOOL          [u16 len + utf8 bytes] × m

为什么要分三种哈希：
    历史 feed 混有 MD5(32)/SHA-1(40)/SHA-256(64)，旧版一律塞进 sha256 字段，
    导致 144 万条 MD5 永远查不到。v2 给每种算法独立表，按各自长度二分。
"""

import argparse
import array
import json
import os
import struct
import sys

MAGIC = b"POLARISB"
FORMAT_VERSION = 2
HEADER_SIZE = 128
NONE = 0xFFFFFFFF

SHA256_REC = 36   # sha256[32] + u32 detailIndex
MD5_REC = 20      # md5[16]    + u32 detailIndex
SHA1_REC = 24     # sha1[20]   + u32 detailIndex
IDX_REC = 12
DETAIL_REC = 24


def fnv1a64(data: bytes) -> int:
    h = 0xCBF29CE484222325
    for b in data:
        h ^= b
        h = (h * 0x100000001B3) & 0xFFFFFFFFFFFFFFFF
    return h


class StringDict:
    """字符串字典：去重后写入池，返回自增 id。"""

    def __init__(self):
        self.ids = {}
        self.blobs = []          # 每个字符串的 (u16 len + utf8)
        self.total = 0

    def intern(self, s):
        if s is None:
            return NONE
        if s == "":
            return NONE
        i = self.ids.get(s)
        if i is not None:
            return i
        raw = s.encode("utf-8")
        if len(raw) > 0xFFFF:
            raw = raw[:0xFFFF]
        i = len(self.blobs)
        self.ids[s] = i
        self.blobs.append(struct.pack("<H", len(raw)) + raw)
        self.total += 2 + len(raw)
        return i

    def id_of(self, s):
        return self.ids.get(s, NONE)


def iter_entries(path, chunk=1 << 20):
    """流式解析 JSON 的 entries 数组，逐条 yield dict（不整份载入内存）。"""
    decoder = json.JSONDecoder()
    f = open(path, "r", encoding="utf-8")
    buf = ""
    # 定位到 "entries" 数组的起始 '['
    while True:
        data = f.read(chunk)
        if not data:
            f.close()
            return
        buf += data
        k = buf.find('"entries"')
        if k >= 0:
            j = buf.find("[", k)
            if j >= 0:
                buf = buf[j + 1:]
                break

    pos = 0
    while True:
        # 跳过空白与逗号
        while True:
            while pos < len(buf) and buf[pos] in " \r\n\t,":
                pos += 1
            if pos < len(buf):
                if buf[pos] == "]":
                    f.close()
                    return
                break
            more = f.read(chunk)
            if not more:
                f.close()
                return
            buf = buf[pos:] + more
            pos = 0
        try:
            obj, end = decoder.raw_decode(buf, pos)
        except ValueError:
            more = f.read(chunk)
            if not more:
                f.close()
                return
            buf = buf[pos:] + more
            pos = 0
            continue
        yield obj
        pos = end
        if pos > chunk:
            buf = buf[pos:]
            pos = 0


def read_head(path):
    """读取 JSON 头部的 db_version / updated_at / source（只读前 4KB）。"""
    version, updated, source = 0, "", ""
    with open(path, "r", encoding="utf-8") as f:
        head = f.read(4096)
    try:
        # 头部形如 {"db_version":14,"count": 1610800, "updated_at":"...", "entries":[ ...
        k = head.find('"entries"')
        if k > 0:
            head = head[:k].rstrip().rstrip(",") + "}"
            obj = json.loads(head)
            version = int(obj.get("db_version", 0) or 0)
            updated = str(obj.get("updated_at", "") or "")
            source = str(obj.get("source", "") or "")
    except Exception as e:
        print("  ! 头部解析失败（忽略）:", e, file=sys.stderr)
    return version, updated, source


def compile_db(src, dst, limit=0):
    version, updated, source = read_head(src)
    print(f"源库: {src}")
    print(f"  db_version={version} updated_at={updated!r} source={source!r}")

    strs = StringDict()
    details = array.array("I")    # 每条 6 个 u32
    sha256_rows = []              # (bytes32, detailIndex)
    md5_rows = []                 # (bytes16, detailIndex)
    sha1_rows = []                # (bytes20, detailIndex)
    pkg_rows = []                 # (hash64, detailIndex)
    url_rows = []

    n = 0
    for e in iter_entries(src):
        sha = (e.get("sha256") or "").strip().lower()
        md5 = (e.get("md5") or "").strip().lower()
        sha1 = (e.get("sha1") or "").strip().lower()
        pkg = (e.get("pkg") or "").strip()
        url = (e.get("url") or "").strip()
        detail_index = n

        # 兼容未迁移的旧数据：MD5/SHA-1 曾被写进 sha256 字段
        if len(sha) == 32 and not md5:
            md5, sha = sha, ""
        elif len(sha) == 40 and not sha1:
            sha1, sha = sha, ""

        details.append(strs.intern(pkg))
        details.append(strs.intern(e.get("family") or ""))
        details.append(strs.intern(e.get("type") or ""))
        details.append(strs.intern(e.get("severity") or "medium"))
        details.append(strs.intern(e.get("desc") or ""))
        details.append(strs.intern(url))

        if len(sha) == 64:
            sha256_rows.append((bytes.fromhex(sha), detail_index))
        if len(md5) == 32:
            md5_rows.append((bytes.fromhex(md5), detail_index))
        if len(sha1) == 40:
            sha1_rows.append((bytes.fromhex(sha1), detail_index))
        if pkg:
            pkg_rows.append((fnv1a64(pkg.encode("utf-8")), detail_index))
        if url:
            url_rows.append((fnv1a64(url.lower().encode("utf-8")), detail_index))

        n += 1
        if limit and n >= limit:
            break
        if n % 200000 == 0:
            print(f"  ... {n} 条")

    # 头部元信息也进字符串池（必须在生成 str_offsets 之前 intern）
    updated_id = strs.intern(updated)
    source_id = strs.intern(source)
    print(f"  共 {n} 条；sha256={len(sha256_rows)} md5={len(md5_rows)} sha1={len(sha1_rows)}；"
          f"包名 {len(pkg_rows)}；URL {len(url_rows)}；字符串 {len(strs.blobs)}")

    # 排序：各哈希表按自身字节序；包名/URL 按其 64 位哈希
    sha256_rows.sort(key=lambda r: r[0])
    md5_rows.sort(key=lambda r: r[0])
    sha1_rows.sort(key=lambda r: r[0])
    pkg_rows.sort()
    url_rows.sort()

    def blob_of(rows):
        b = bytearray()
        for h, d in rows:
            b += h
            b += struct.pack("<I", d)
        return b

    sha256_blob = blob_of(sha256_rows)
    md5_blob = blob_of(md5_rows)
    sha1_blob = blob_of(sha1_rows)

    sha256_off = HEADER_SIZE
    md5_off = sha256_off + len(sha256_blob)
    sha1_off = md5_off + len(md5_blob)
    pkg_off = sha1_off + len(sha1_blob)
    url_off = pkg_off + len(pkg_rows) * IDX_REC
    detail_off = url_off + len(url_rows) * IDX_REC
    detail_blob = details.tobytes()
    str_index_off = detail_off + len(detail_blob)
    str_count = len(strs.blobs)
    str_pool_off = str_index_off + str_count * 4

    str_offsets = array.array("I")
    str_pool = bytearray()
    for b in strs.blobs:
        str_offsets.append(len(str_pool))
        str_pool += b

    pkg_blob = bytearray()
    for h, d in pkg_rows:
        pkg_blob += struct.pack("<QI", h, d)
    url_blob = bytearray()
    for h, d in url_rows:
        url_blob += struct.pack("<QI", h, d)

    header = bytearray(HEADER_SIZE)
    header[0:8] = MAGIC
    struct.pack_into("<I", header, 8, FORMAT_VERSION)
    struct.pack_into("<I", header, 12, version)
    struct.pack_into("<I", header, 16, updated_id)
    struct.pack_into("<I", header, 20, source_id)
    struct.pack_into("<I", header, 24, n)
    struct.pack_into("<I", header, 28, len(sha256_rows))
    struct.pack_into("<I", header, 32, len(pkg_rows))
    struct.pack_into("<I", header, 36, len(url_rows))
    struct.pack_into("<I", header, 40, str_count)
    struct.pack_into("<I", header, 44, sha256_off)
    struct.pack_into("<I", header, 48, pkg_off)
    struct.pack_into("<I", header, 52, url_off)
    struct.pack_into("<I", header, 56, detail_off)
    struct.pack_into("<I", header, 60, str_index_off)
    struct.pack_into("<I", header, 64, str_pool_off)
    struct.pack_into("<I", header, 68, len(str_pool))
    # v2: MD5 / SHA-1 独立索引（客户端按各自长度二分）
    struct.pack_into("<I", header, 72, len(md5_rows))
    struct.pack_into("<I", header, 76, md5_off)
    struct.pack_into("<I", header, 80, len(sha1_rows))
    struct.pack_into("<I", header, 84, sha1_off)

    with open(dst, "wb") as f:
        f.write(header)
        f.write(sha256_blob)
        f.write(md5_blob)
        f.write(sha1_blob)
        f.write(pkg_blob)
        f.write(url_blob)
        f.write(detail_blob)
        f.write(str_offsets.tobytes())
        f.write(str_pool)

    size = os.path.getsize(dst)
    print(f"输出: {dst}  {size/1024/1024:.1f} MB")
    print(f"  sha256 {len(sha256_blob)/1024/1024:.1f} MB, md5 {len(md5_blob)/1024/1024:.1f} MB, "
          f"sha1 {len(sha1_blob)/1024/1024:.1f} MB, 明细 {len(detail_blob)/1024/1024:.1f} MB, "
          f"字符串 {len(str_pool)/1024/1024:.1f} MB")
    return 0


def main():
    ap = argparse.ArgumentParser(description="Polaris iodb.json → iodb.bin 编译器")
    ap.add_argument("src", help="输入 iodb.json")
    ap.add_argument("dst", help="输出 iodb.bin")
    ap.add_argument("--limit", type=int, default=0, help="只取前 N 条（调试用）")
    args = ap.parse_args()
    return compile_db(args.src, args.dst, args.limit)


if __name__ == "__main__":
    sys.exit(main())

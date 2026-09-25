#!/usr/bin/env python3
"""
DEX 指令流 walker：验证 opcode 宽度表 + 提取 5 个目标 opcode 是否存在。
硬校验：每个 code_item 走完必须精确落在 insns_size 上，否则宽度表有错。
用法: python dex_walk.py <apk>
"""
import sys, zipfile

TARGET = {0x1a: "const-string", 0x6e: "invoke-virtual", 0x71: "invoke-static",
          0x0c: "move-result-object", 0x38: "if-eqz"}


def build_width():
    w = [1] * 256
    ex = {0x01:1,0x02:2,0x03:3,0x04:1,0x05:2,0x06:3,0x07:1,0x08:2,0x09:3,
          0x0a:1,0x0b:1,0x0c:1,0x0d:1,0x0e:1,0x0f:1,0x10:1,0x11:1,
          0x12:1,0x13:2,0x14:3,0x15:2,0x16:2,0x17:3,0x18:5,0x19:2,
          0x1a:2,0x1b:3,0x1c:2,0x1d:1,0x1e:1,0x1f:2,
          0x20:2,0x21:1,0x22:2,0x23:2,0x24:3,0x25:3,0x26:3,0x27:1,
          0x28:1,0x29:2,0x2a:3,0x2b:3,0x2c:3,
          0xfa:4,0xfb:4,0xfc:3,0xfd:3,0xfe:2,0xff:2}
    for k,v in ex.items(): w[k]=v
    for r in [(0x2d,0x32),(0x32,0x38),(0x38,0x3e),(0x44,0x52),(0x52,0x60),
              (0x60,0x6e),(0x90,0xb0),(0xd0,0xd8),(0xd8,0xe3)]:
        for i in range(*r): w[i]=2
    for r in [(0x6e,0x73),(0x74,0x79)]:
        for i in range(*r): w[i]=3
    for i in range(0x7b,0x90): w[i]=1
    for i in range(0xb0,0xd0): w[i]=1
    return w


WIDTH = build_width()
UNUSED = set(range(0x3e, 0x44)) | {0x73, 0x79, 0x7a} | set(range(0xe3, 0xfa))


def u32(d,o):
    if o<0 or o+4>len(d): return -1
    return d[o]|(d[o+1]<<8)|(d[o+2]<<16)|(d[o+3]<<24)
def u16(d,o):
    if o<0 or o+2>len(d): return -1
    return d[o]|(d[o+1]<<8)
def uleb(d,o):
    r=0; s=0
    while o<len(d):
        b=d[o]; o+=1
        r |= (b&0x7f)<<s
        if not (b&0x80): break
        s+=7
    return r,o


def walk(insns, size, hits):
    pos=0
    n=len(insns)
    trace=[]
    bad=[]
    while pos < size:
        if pos >= n: return -1, trace, bad
        unit = insns[pos] & 0xffff
        op = unit & 0xff
        if op in UNUSED:
            bad.append((pos, op, unit))
        if op == 0x00:
            if unit == 0x0000:
                w = 1
            elif unit == 0x0100:
                w = 2 + 2*(insns[pos+1] & 0xffff)
            elif unit == 0x0200:
                w = 2 + 4*(insns[pos+1] & 0xffff)
            elif unit == 0x0300:
                ew = insns[pos+1] & 0xffff
                sz = (insns[pos+2] & 0xffff) | ((insns[pos+3] & 0xffff) << 16)
                w = 4 + (sz*ew + 1)//2
            else:
                return -2, trace, bad
            trace.append((pos, op, unit, w))
            pos += w
            continue
        if op in TARGET:
            hits.add(op)
        w = WIDTH[op]
        trace.append((pos, op, unit, w))
        pos += w
    return pos, trace, bad


def parse(dex, hits, stats):
    if len(dex) < 112 or dex[:3] != b"dex": return
    cdsize, cdoff = u32(dex,0x60), u32(dex,0x64)
    for ci in range(cdsize):
        base = cdoff + ci*32
        cdo = u32(dex, base+24)
        if cdo <= 0: continue
        o = cdo
        sf,o = uleb(dex,o); inf,o = uleb(dex,o); dm,o = uleb(dex,o); vm,o = uleb(dex,o)
        for _ in range(sf): _,o = uleb(dex,o); _,o = uleb(dex,o)
        for _ in range(inf): _,o = uleb(dex,o); _,o = uleb(dex,o)
        code_offs=[]
        for _ in range(dm):
            _,o = uleb(dex,o); _,o = uleb(dex,o); co,o = uleb(dex,o); code_offs.append(co)
        for _ in range(vm):
            _,o = uleb(dex,o); _,o = uleb(dex,o); co,o = uleb(dex,o); code_offs.append(co)
        for co in code_offs:
            if co <= 0: continue
            insns_size = u32(dex, co+12)
            ins_start = co+16
            if insns_size <= 0 or ins_start + insns_size*2 > len(dex): continue
            insns = [u16(dex, ins_start+i*2) for i in range(insns_size)]
            local = set()
            r, trace, bad = walk(insns, insns_size, local)
            stats["methods"] += 1
            if r == insns_size:
                stats["exact"] += 1
                hits |= local        # 仅精确走完的方法才采信
            else:
                stats["mismatch"] += 1
                if stats["mismatch"] <= 3:
                    print(f"  失败: 走完={r} 期望={insns_size}  (丢弃该方法的 opcode)")
                    print(f"     最后 6 步:", [(t[0], hex(t[1]), t[3]) for t in trace[-6:]])


def scan_apk(apk):
    hits = set()
    stats = {"methods": 0, "exact": 0, "mismatch": 0, "samples": []}
    z = zipfile.ZipFile(apk)
    for n in z.namelist():
        if n.endswith(".dex"):
            parse(z.read(n), hits, stats)
    return hits, stats


def main():
    apk = sys.argv[1]
    hits, stats = scan_apk(apk)
    print(f"code_item 方法数={stats['methods']}  精确走完={stats['exact']}  不一致={stats['mismatch']}")
    if stats["samples"]:
        print("  失败样例(走完位置, 期望):", stats["samples"])
    print("\n5 个目标 opcode 是否存在:")
    for op,name in TARGET.items():
        print(f"  {name:20s} {'存在' if op in hits else '不存在'}")
    print("\n结论:", "宽度表正确 ✔" if stats["mismatch"]==0 else "宽度表有问题 ✘")


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""
mh1m 端到端 sanity check：用真实 APK 计算 43 维特征并跑 mh1m 模型。
复刻 DexParser.extractSignals 的 Java 逻辑（字符串池 + method_ids）。
用法: python mh1m_sanity.py <apk>
"""
import sys, os, json, re, zipfile, subprocess

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
AAPT = r"C:/Android/Sdk/build-tools/36.0.0/aapt.exe"

PERMS = ["android.permission.INTERNET", "android.permission.READ_SMS", "android.permission.SEND_SMS",
         "android.permission.RECEIVE_SMS", "android.permission.BROADCAST_SMS", "android.permission.READ_CONTACTS",
         "android.permission.WRITE_CONTACTS", "android.permission.READ_PHONE_STATE", "android.permission.READ_CALL_LOG",
         "android.permission.CALL_PHONE", "android.permission.ACCESS_FINE_LOCATION", "android.permission.ACCESS_COARSE_LOCATION",
         "android.permission.CAMERA", "android.permission.RECORD_AUDIO", "android.permission.READ_EXTERNAL_STORAGE",
         "android.permission.WRITE_EXTERNAL_STORAGE", "android.permission.RECEIVE_BOOT_COMPLETED", "android.permission.SYSTEM_ALERT_WINDOW",
         "android.permission.GET_ACCOUNTS", "android.permission.PROCESS_OUTGOING_CALLS"]
INTENTS = ["android.intent.action.BOOT_COMPLETED", "android.intent.action.PACKAGE_ADDED",
           "android.intent.action.ACTION_POWER_CONNECTED", "android.intent.action.USER_PRESENT",
           "android.intent.action.NEW_OUTGOING_CALL", "android.intent.action.ACTION_SHUTDOWN",
           "android.net.conn.CONNECTIVITY_CHANGE", "android.intent.action.PHONE_STATE"]
APIS = ["Landroid/telephony/SmsManager;->sendTextMessage", "Landroid/telephony/TelephonyManager;->getDeviceId",
        "Landroid/telephony/TelephonyManager;->getSubscriberId", "Landroid/content/pm/PackageManager;->getInstalledPackages",
        "Landroid/content/pm/PackageManager;->queryIntentActivities", "Landroid/webkit/WebView;->loadData",
        "Landroid/net/wifi/WifiManager;->getConnectionInfo", "Landroid/telephony/SmsManager;->sendMultipartTextMessage",
        "Landroid/content/pm/PackageManager;->queryIntentServices", "Landroid/telephony/TelephonyManager;->getLine1Number"]


def read_int(d, o):
    if o < 0 or o + 4 > len(d): return -1
    return d[o] | (d[o+1] << 8) | (d[o+2] << 16) | (d[o+3] << 24)


def read_ushort(d, o):
    if o < 0 or o + 2 > len(d): return -1
    return d[o] | (d[o+1] << 8)


def read_mutf8(d, o):
    if o <= 0 or o >= len(d): return None
    p = o
    while p < len(d) and (d[p] & 0x80): p += 1
    p += 1
    s = p
    while p < len(d) and d[p] != 0: p += 1
    return d[s:p].decode("utf-8", "ignore")


def dex_signals(apk):
    strings, refs = set(), set()
    z = zipfile.ZipFile(apk)
    for n in z.namelist():
        if not n.endswith(".dex"):
            continue
        d = z.read(n)
        if d[:3] != b"dex":
            continue
        ssize, soff = read_int(d, 0x38), read_int(d, 0x3C)
        tsize, toff = read_int(d, 0x40), read_int(d, 0x44)
        msize, moff = read_int(d, 0x58), read_int(d, 0x5C)
        strs = [None] * ssize
        for i in range(ssize):
            s = read_mutf8(d, read_int(d, soff + i * 4))
            strs[i] = s
            if s: strings.add(s)
        types = [None] * tsize
        for i in range(tsize):
            di = read_int(d, toff + i * 4)
            types[i] = strs[di] if 0 <= di < ssize else None
        for i in range(msize):
            b = moff + i * 8
            if b + 8 > len(d): break
            ci, ni = read_ushort(d, b), read_int(d, b + 4)
            cls = types[ci] if 0 <= ci < tsize else None
            nm = strs[ni] if 0 <= ni < ssize else None
            if cls and nm: refs.add(cls + "->" + nm)
    return strings, refs


def main():
    apk = sys.argv[1]
    out = subprocess.check_output([AAPT, "dump", "permissions", apk]).decode("utf-8", "ignore")
    perms = set(re.findall(r"uses-permission: name='([^']+)'", out))
    strings, refs = dex_signals(apk)
    import importlib.util
    spec = importlib.util.spec_from_file_location("dex_walk", os.path.join(ROOT, "ml/eval/dex_walk.py"))
    dw = importlib.util.module_from_spec(spec); spec.loader.exec_module(dw)
    ophits, ostats = dw.scan_apk(apk)
    print(f"APK={os.path.basename(apk)}  权限={len(perms)} 字符串={len(strings)} 方法引用={len(refs)}")
    print(f"  DEX 方法 {ostats['methods']} (精确 {ostats['exact']} / 丢弃 {ostats['mismatch']})  opcode 命中={[hex(o) for o in sorted(ophits)]}")

    x = [0.0] * 43
    hit = []
    for i, p in enumerate(PERMS):
        if p in perms: x[i] = 1.0; hit.append(f"perm:{p.split('.')[-1]}")
    for i, a in enumerate(INTENTS):
        if a in strings: x[20+i] = 1.0; hit.append(f"intent:{a.split('.')[-1]}")
    for i, op in enumerate([0x1a, 0x6e, 0x71, 0x0c, 0x38]):
        if op in ophits:
            x[28+i] = 1.0; hit.append(f"opcode:0x{op:02x}")
    for i, a in enumerate(APIS):
        if a in refs: x[33+i] = 1.0; hit.append(f"api:{a.split('->')[1]}")

    trees = json.load(open(os.path.join(ROOT, "app/src/main/assets/model_mh1m.json")))["trees"]
    sc = json.load(open(os.path.join(ROOT, "app/src/main/assets/scaler_mh1m.json")))
    mean, std = sc["mean"], sc["std"]
    xn = [(x[i] - mean[i]) / std[i] for i in range(43)]
    s = 0.0
    for r in trees:
        n = r
        while "leaf" not in n:
            n = n["left"] if xn[n["split_feature"]] < n["split_condition"] else n["right"]
        s += n["leaf"]
    prob = 1.0 / (1.0 + 2.718281828459045 ** (-s))
    thr = json.load(open(os.path.join(ROOT, "app/src/main/assets/metadata_mh1m.json")))["threshold"]
    print(f"命中特征({len(hit)}): {hit}")
    print(f"\nmh1m 概率 = {prob:.4f}  阈值 = {thr}  =>  {'恶意' if prob>=thr else '正常'}")


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""
在真实 MH-100K 样本上评估 app 内置 LR 模型 (malware_detector_v2.json)。
纯 Python，无第三方依赖。目的：验证“合成数据训练的 LR”在真实数据上的表现，
并量化 app 运行时 HashMap 特征顺序错位的影响。

用法: python lr_real_eval.py [max_rows]
"""
import json, sys, os

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
ASSETS = os.path.join(ROOT, "app", "src", "main", "assets")
CSV = os.path.join(ROOT, "ml", "data", "raw", "mh100.csv")

MAX_ROWS = int(sys.argv[1]) if len(sys.argv) > 1 else 20000

# ---- app 源码里的特征定义 (DexParser / MlScanner) ----
PERM_COMBOS_DECL = [
    ("SMS_MALWARE", ["READ_SMS", "SEND_SMS", "RECEIVE_SMS", "RECEIVE_MMS", "RECEIVE_WAP_PUSH"]),
    ("PHONE_MALWARE", ["READ_PHONE_STATE", "CALL_PHONE", "PROCESS_OUTGOING_CALLS", "READ_CALL_LOG", "WRITE_CALL_LOG"]),
    ("CONTACTS_MALWARE", ["READ_CONTACTS", "WRITE_CONTACTS", "GET_ACCOUNTS"]),
    ("LOCATION_MALWARE", ["ACCESS_FINE_LOCATION", "ACCESS_COARSE_LOCATION", "ACCESS_BACKGROUND_LOCATION"]),
    ("CAMERA_MALWARE", ["CAMERA", "RECORD_AUDIO"]),
    ("STORAGE_MALWARE", ["READ_EXTERNAL_STORAGE", "WRITE_EXTERNAL_STORAGE", "MANAGE_EXTERNAL_STORAGE"]),
    ("ADMIN_MALWARE", ["BIND_DEVICE_ADMIN", "INSTALL_PACKAGES", "DELETE_PACKAGES", "BIND_ACCESSIBILITY_SERVICE"]),
    ("OVERLAY_MALWARE", ["SYSTEM_ALERT_WINDOW", "BIND_ACCESSIBILITY_SERVICE"]),
    ("CLIPBOARD_MALWARE", ["READ_CLIPBOARD", "WRITE_CLIPBOARD"]),
    ("CALENDAR_MALWARE", ["READ_CALENDAR", "WRITE_CALENDAR"]),
    ("SENSOR_MALWARE", ["BODY_SENSORS", "ACTIVITY_RECOGNITION"]),
    ("BLUETOOTH_MALWARE", ["BLUETOOTH", "BLUETOOTH_ADMIN", "BLUETOOTH_SCAN", "BLUETOOTH_CONNECT"]),
    ("WIFI_MALWARE", ["ACCESS_WIFI_STATE", "CHANGE_WIFI_STATE", "CHANGE_NETWORK_STATE"]),
    ("NFC_MALWARE", ["NFC"]),
    ("NOTIFICATION_MALWARE", ["POST_NOTIFICATIONS", "RECEIVE_BOOT_COMPLETED"]),
]
# 训练顺序(声明顺序) == FEATURE_NAMES 顺序
TRAIN_ORDER = [c[0] for c in PERM_COMBOS_DECL]
# app 运行时 Java HashMap 桶顺序(已在 Python 中复刻验证)
HASHMAP_ORDER = ["LOCATION_MALWARE","CAMERA_MALWARE","STORAGE_MALWARE","PHONE_MALWARE",
                 "WIFI_MALWARE","CONTACTS_MALWARE","BLUETOOTH_MALWARE","NOTIFICATION_MALWARE",
                 "OVERLAY_MALWARE","SMS_MALWARE","SENSOR_MALWARE","CLIPBOARD_MALWARE",
                 "CALENDAR_MALWARE","ADMIN_MALWARE","NFC_MALWARE"]

API_CATEGORIES = [
    ("reflection", ["java.lang.reflect.Method.invoke", "java.lang.reflect.Field.setAccessible", "java.lang.Class.forName"]),
    ("dynamic_load", ["dalvik.system.DexClassLoader", "dalvik.system.PathClassLoader", "java.lang.ClassLoader.loadClass"]),
    ("crypto", ["javax.crypto.Cipher", "javax.crypto.SecretKeySpec", "java.security.MessageDigest"]),
    ("network", ["java.net.HttpURLConnection", "java.net.URL.openConnection", "org.apache.http.impl.client.DefaultHttpClient"]),
    ("sms", ["android.telephony.SmsManager", "android.telephony.SmsMessage"]),
    ("phone", ["android.telephony.TelephonyManager", "android.telephony.PhoneStateListener"]),
    ("contacts", ["android.content.ContentResolver", "android.provider.ContactsContract"]),
    ("location", ["android.location.LocationManager", "android.location.LocationListener"]),
    ("camera", ["android.hardware.Camera", "android.hardware.camera2.CameraManager"]),
    ("audio", ["android.media.MediaRecorder", "android.media.AudioRecord"]),
    ("file", ["java.io.File", "java.io.FileInputStream", "java.io.FileOutputStream"]),
    ("shared_prefs", ["android.content.SharedPreferences"]),
    ("intent", ["android.content.Intent", "android.content.IntentFilter"]),
    ("service", ["android.app.Service", "android.app.IntentService"]),
    ("broadcast", ["android.content.BroadcastReceiver"]),
    ("content_provider", ["android.content.ContentProvider"]),
    ("notification", ["android.app.NotificationManager"]),
    ("accessibility", ["android.accessibilityservice.AccessibilityService", "android.view.accessibility.AccessibilityEvent"]),
    ("device_admin", ["android.app.admin.DeviceAdminReceiver", "android.app.admin.DevicePolicyManager"]),
    ("package", ["android.content.pm.PackageManager", "android.content.pm.PackageInfo"]),
]
DANGEROUS = {"READ_SMS","SEND_SMS","READ_PHONE_STATE","CALL_PHONE","READ_CONTACTS",
             "ACCESS_FINE_LOCATION","CAMERA","RECORD_AUDIO","READ_EXTERNAL_STORAGE",
             "WRITE_EXTERNAL_STORAGE","READ_CALL_LOG","PROCESS_OUTGOING_CALLS",
             "READ_CALENDAR","BODY_SENSORS"}


def norm_api(col):
    if not col.startswith("APICall::"):
        return None
    n = col[len("APICall::"):]
    if n.startswith("L"):
        n = n[1:]
    n = n.replace("/", ".")
    n = n.split("(")[0]
    return n


def load_lr():
    with open(os.path.join(ASSETS, "malware_detector_v2.json")) as f:
        m = json.load(f)
    with open(os.path.join(ASSETS, "malware_detector_v2_metadata.json")) as f:
        md = json.load(f)
    coef = m["coef"][0]
    intercept = m["intercept"][0]
    mean = m["scaler_mean"]
    scale = m["scaler_scale"]
    thr = md.get("threshold", 0.5)
    return coef, intercept, mean, scale, thr, md


def auc(y, s):
    # 秩和法 (Mann-Whitney)
    pairs = sorted(zip(s, y))
    ranks = [0.0] * len(pairs)
    i = 0
    while i < len(pairs):
        j = i
        while j + 1 < len(pairs) and pairs[j + 1][0] == pairs[i][0]:
            j += 1
        avg = (i + j) / 2.0 + 1
        for k in range(i, j + 1):
            ranks[k] = avg
        i = j + 1
    pos = [r for r, (_, yy) in zip(ranks, pairs) if yy == 1]
    npos, nneg = len(pos), len(pairs) - len(pos)
    if npos == 0 or nneg == 0:
        return float("nan")
    return (sum(pos) - npos * (npos + 1) / 2.0) / (npos * nneg)


def avg_precision(y, s):
    order = sorted(range(len(s)), key=lambda i: -s[i])
    tp = fp = 0
    prev_rec = 0.0
    ap = 0.0
    npos = sum(y)
    if npos == 0:
        return float("nan")
    for idx in order:
        if y[idx] == 1:
            tp += 1
            rec = tp / npos
            prec = tp / (tp + fp)
            ap += prec * (rec - prev_rec)
            prev_rec = rec
        else:
            fp += 1
    return ap


def main():
    coef, intercept, mean, scale, thr, md = load_lr()
    print(f"模型: malware_detector_v2.json  trained_on={md.get('trained_on')}")
    print(f"  metadata 声明指标: acc={md.get('accuracy')} f1={md.get('f1_score')} "
          f"prec={md.get('precision')} rec={md.get('recall')}  (来自合成测试集)")

    with open(CSV, "r") as f:
        header = f.readline().rstrip("\n").split(",")
    ncol = len(header)
    idx_of = {name: i for i, name in enumerate(header)}

    perm_idx = {c[len("Permission::"):]: i for c, i in idx_of.items() if c.startswith("Permission::")}
    all_perms = list(perm_idx.keys())
    # api 类别 -> 匹配到的列索引
    api_cols = {}
    for ci, c in enumerate(header):
        nn = norm_api(c)
        if nn is None:
            continue
        for cat, sigs in API_CATEGORIES:
            if any(s.lower() in nn.lower() for s in sigs):
                api_cols.setdefault(cat, []).append(ci)
                break
    class_idx = idx_of.get("class", ncol - 1)

    # 组合特征 -> 各 perm 的列索引 (缺失的 perm 记为 None)
    combo_cols = {}
    for name, perms in PERM_COMBOS_DECL:
        combo_cols[name] = [perm_idx.get(p) for p in perms]

    covered = sum(1 for _, perms in PERM_COMBOS_DECL for p in perms if p in perm_idx)
    total = sum(len(perms) for _, perms in PERM_COMBOS_DECL)
    print(f"CSV 列数: {ncol}; Permission 列: {len(all_perms)}; "
          f"combo 需要的权限在 CSV 中可覆盖 {covered}/{total}; api 类别命中 {len(api_cols)}/20")

    X, Y = [], []
    stride = int(sys.argv[2]) if len(sys.argv) > 2 else 1
    with open(CSV, "r") as f:
        f.readline()
        processed = 0
        for n, line in enumerate(f):
            if n % stride != 0:
                continue
            if processed >= MAX_ROWS:
                break
            processed += 1
            parts = line.rstrip("\n").split(",")
            if len(parts) < ncol:
                continue
            try:
                lab = int(float(parts[class_idx] or 0))
            except ValueError:
                continue
            combo = {}
            for name, cols in combo_cols.items():
                ok = True
                for ci in cols:
                    if ci is None or parts[ci] in ("", "0", "0.0"):
                        ok = False
                        break
                combo[name] = 1.0 if ok else 0.0
            perm_present = [1 for p in all_perms if parts[perm_idx[p]] not in ("", "0", "0.0")]
            nperm = sum(perm_present)
            ndanger = sum(1 for p in all_perms if p in DANGEROUS and parts[perm_idx[p]] not in ("", "0", "0.0"))
            if "INTERNET" in perm_idx:
                internet = 1.0 if parts[perm_idx["INTERNET"]] not in ("", "0", "0.0") else 0.0
            else:
                internet = 0.0
            api = []
            for cat, _ in API_CATEGORIES:
                v = 0.0
                for ci in api_cols.get(cat, []):
                    if parts[ci] not in ("", "0", "0.0"):
                        v = 1.0
                        break
                api.append(v)
            # 43 维基线(训练顺序)
            base = ([combo[c[0]] for c in PERM_COMBOS_DECL] +
                    [0.0, 0.0, 0.0, min(nperm / 50.0, 1.0), min(ndanger / 20.0, 1.0), 0.5, 1.0, internet] +
                    api)
            # HashMap 顺序版本
            hm = ([combo[c] for c in HASHMAP_ORDER] + base[15:])
            X.append((base, hm))
            Y.append(lab)

    print(f"样例数: {len(Y)}  恶意={sum(Y)}  良性={len(Y) - sum(Y)}")

    def score(vec):
        z = intercept
        for i in range(43):
            s = scale[i] if scale[i] != 0 else 1.0
            z += coef[i] * ((vec[i] - mean[i]) / s)
        return 1.0 / (1.0 + pow(2.718281828459045, -z))

    for label, pick in (("训练顺序(FEATURE_NAMES, 模型期望)", 0),
                        ("app 运行时(HashMap 桶顺序)", 1)):
        sc = [score(row[pick]) for row in X]
        a = auc(Y, sc)
        ap = avg_precision(Y, sc)
        pred = [1 if v >= thr else 0 for v in sc]
        tp = sum(1 for i in range(len(Y)) if pred[i] == 1 and Y[i] == 1)
        fp = sum(1 for i in range(len(Y)) if pred[i] == 1 and Y[i] == 0)
        fn = sum(1 for i in range(len(Y)) if pred[i] == 0 and Y[i] == 1)
        prec = tp / (tp + fp) if tp + fp else 0.0
        rec = tp / (tp + fn) if tp + fn else 0.0
        f1 = 2 * prec * rec / (prec + rec) if prec + rec else 0.0
        print(f"\n[{label}]")
        print(f"  ROC-AUC={a:.4f}  PR-AUC(AP)={ap:.4f}")
        print(f"  @阈值{thr}: precision={prec:.4f} recall={rec:.4f} f1={f1:.4f}")


if __name__ == "__main__":
    main()

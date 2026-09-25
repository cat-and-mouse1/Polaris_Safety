#!/usr/bin/env python3
"""
Polaris 真实数据训练管线 (v3)。
从真实 MH-100K (ml/data/raw/mh100.csv) 派生 38 维特征并训练逻辑回归。

38 维布局（必须与 app 侧 MlScanner.extractFeatures / FEATURE_NAMES 完全一致）：
  0-14  : 15 个权限组合 (combo_*)
  15-17 : permission_count, dangerous_perm_count, has_internet
  18-37 : 20 个 DEX API 类别 (api_*)

输出: app/src/main/assets/malware_detector_v3.json + malware_detector_v3_metadata.json
依赖: numpy
"""
import json, os, sys, time
import numpy as np

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
CSV = os.path.join(ROOT, "ml", "data", "raw", "mh100.csv")
ASSETS = os.path.join(ROOT, "app", "src", "main", "assets")
CACHE = os.path.join(ROOT, "ml", "eval", "real_features_cache.npz")

STRIDE = int(sys.argv[1]) if len(sys.argv) > 1 else 3
SEED = 42
np.random.seed(SEED)

# ---------------- 与 app 侧一致的特征定义 ----------------
PERM_COMBOS = [
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
DANGEROUS = ["READ_SMS", "SEND_SMS", "READ_PHONE_STATE", "CALL_PHONE", "READ_CONTACTS",
             "ACCESS_FINE_LOCATION", "CAMERA", "RECORD_AUDIO", "READ_EXTERNAL_STORAGE",
             "WRITE_EXTERNAL_STORAGE", "READ_CALL_LOG", "PROCESS_OUTGOING_CALLS",
             "READ_CALENDAR", "BODY_SENSORS"]

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

FEATURE_NAMES = ([f"combo_{n}" for n, _ in PERM_COMBOS] +
                 ["ctx_permission_count", "ctx_dangerous_perm_count", "ctx_has_internet_permission"] +
                 [f"api_{n}" for n, _ in API_CATEGORIES])


def norm_api_col(col):
    """'APICall::Landroid/telephony/SmsManager.sendTextMessage()' -> 'android/telephony/SmsManager/sendTextMessage'"""
    n = col[len("APICall::"):]
    if n.startswith("L"):
        n = n[1:]
    n = n.replace(".", "/")
    return n.split("(")[0]


def sig_variants(sig):
    """点号签名 -> 斜杠匹配串列表（全名 + 方法级的类前缀）。"""
    v = [sig.replace(".", "/")]
    last = sig.rsplit(".", 1)[-1]
    if last and last[0].islower() and "." in sig:
        v.append(sig.rsplit(".", 1)[0].replace(".", "/"))
    return v


def build_index(header):
    idx = {c: i for i, c in enumerate(header)}
    perm_short2col = {c[len("Permission::"):]: i for c, i in idx.items() if c.startswith("Permission::")}
    all_perm_cols = list(perm_short2col.values())
    api_cols = {}
    norm_cols = {}
    for c, i in idx.items():
        if c.startswith("APICall::"):
            norm_cols[i] = norm_api_col(c)
    cat_cols = {name: [] for name, _ in API_CATEGORIES}
    for i, nc in norm_cols.items():
        for name, sigs in API_CATEGORIES:
            if any(any(v in nc for v in sig_variants(s)) for s in sigs):
                cat_cols[name].append(i)
                break
    return idx, perm_short2col, all_perm_cols, cat_cols


def extract():
    if os.path.exists(CACHE):
        d = np.load(CACHE)
        print(f"[cache] 载入 {CACHE}: X={d['X'].shape} 恶意={int(d['y'].sum())}")
        return d["X"], d["y"]
    with open(CSV) as f:
        header = f.readline().rstrip("\n").split(",")
    ncol = len(header)
    idx, perm_short2col, all_perm_cols, cat_cols = build_index(header)
    class_idx = idx.get("class", ncol - 1)
    missing = [p for _, perms in PERM_COMBOS for p in perms if p not in perm_short2col]
    print(f"列数={ncol} Permission列={len(all_perm_cols)} class@{class_idx} "
          f"api类别命中={sum(1 for v in cat_cols.values() if v)}/20")
    if missing:
        print(f"  注意: {len(missing)} 个组合所需权限不在 CSV 中(训练按缺失=不满足): {sorted(set(missing))}")

    dang_cols = [perm_short2col[p] for p in DANGEROUS if p in perm_short2col]
    inet_col = perm_short2col.get("INTERNET")
    X, y = [], []
    with open(CSV) as f:
        f.readline()
        n = 0
        for line in f:
            if n % STRIDE != 0:
                n += 1
                continue
            n += 1
            p = line.rstrip("\n").split(",")
            if len(p) < ncol:
                continue
            row = np.zeros(38, dtype=np.float32)
            # combos
            for ci, (_, perms) in enumerate(PERM_COMBOS):
                ok = True
                for pm in perms:
                    col = perm_short2col.get(pm)
                    if col is None or p[col] in ("", "0", "0.0"):
                        ok = False
                        break
                row[ci] = 1.0 if ok else 0.0
            # context
            present = sum(1 for c in all_perm_cols if p[c] not in ("", "0", "0.0"))
            row[15] = min(present / 50.0, 1.0)
            row[16] = min(sum(1 for c in dang_cols if p[c] not in ("", "0", "0.0")) / 20.0, 1.0)
            row[17] = 1.0 if (inet_col is not None and p[inet_col] not in ("", "0", "0.0")) else 0.0
            # api
            for ai, (name, _) in enumerate(API_CATEGORIES):
                hit = 0.0
                for c in cat_cols.get(name, []):
                    if p[c] not in ("", "0", "0.0"):
                        hit = 1.0
                        break
                row[18 + ai] = hit
            try:
                lab = int(float(p[class_idx] or 0))
            except ValueError:
                continue
            X.append(row)
            y.append(lab)
    X = np.vstack(X)
    y = np.asarray(y, dtype=np.float32)
    np.savez_compressed(CACHE, X=X, y=y)
    print(f"[extract] 样本={len(y)} 恶意={int(y.sum())} 良性={int((y==0).sum())} -> 缓存 {CACHE}")
    return X, y


def sigmoid(z):
    out = np.empty_like(z)
    pos = z >= 0
    out[pos] = 1.0 / (1.0 + np.exp(-z[pos]))
    ez = np.exp(z[~pos])
    out[~pos] = ez / (1.0 + ez)
    return out


def train_lr(Xtr, ytr, iters=2000, lr=0.5, l2=1e-4):
    n, d = Xtr.shape
    npos = max(ytr.sum(), 1.0)
    nneg = max(n - npos, 1.0)
    sw = np.where(ytr == 1, n / (2.0 * npos), n / (2.0 * nneg)).astype(np.float64)
    w = np.zeros(d)
    b = 0.0
    for it in range(iters):
        z = Xtr @ w + b
        pr = sigmoid(z)
        g = sw * (pr - ytr)
        grad_w = (Xtr.T @ g) / n + l2 * w
        grad_b = g.mean()
        w -= lr * grad_w
        b -= lr * grad_b
        if it % 400 == 0:
            loss = -np.mean(sw * (ytr * np.log(pr + 1e-9) + (1 - ytr) * np.log(1 - pr + 1e-9))) + 0.5 * l2 * w @ w
            print(f"  iter {it:5d} loss={loss:.4f}")
    return w, b


def auc(y, s):
    order = np.argsort(s)
    ss = s[order]
    yy = y[order]
    ranks = np.empty(len(ss), dtype=np.float64)
    i = 0
    while i < len(ss):
        j = i
        while j + 1 < len(ss) and ss[j + 1] == ss[i]:
            j += 1
        ranks[i:j + 1] = (i + j) / 2.0 + 1
        i = j + 1
    npos, nneg = yy.sum(), len(yy) - yy.sum()
    return (ranks[yy == 1].sum() - npos * (npos + 1) / 2.0) / (npos * nneg)


def main():
    t0 = time.time()
    X, y = extract()
    n = len(y)
    perm = np.random.permutation(n)
    cut = int(n * 0.8)
    tr, te = perm[:cut], perm[cut:]
    mean = X[tr].mean(axis=0)
    scale = X[tr].std(axis=0)
    scale[scale == 0] = 1.0
    Xtr = (X[tr] - mean) / scale
    Xte = (X[te] - mean) / scale

    print(f"训练 {len(tr)} / 测试 {len(te)} 维={X.shape[1]}")
    w, b = train_lr(Xtr.astype(np.float64), y[tr])

    sc = sigmoid(Xte @ w + b)
    pred = (sc >= 0.5).astype(int)
    yte = y[te]
    tp = int(((pred == 1) & (yte == 1)).sum())
    fp = int(((pred == 1) & (yte == 0)).sum())
    fn = int(((pred == 0) & (yte == 1)).sum())
    prec = tp / (tp + fp) if tp + fp else 0.0
    rec = tp / (tp + fn) if tp + fn else 0.0
    f1 = 2 * prec * rec / (prec + rec) if prec + rec else 0.0
    a = auc(yte, sc)
    print(f"\n[真实留出集] AUC={a:.4f} precision={prec:.4f} recall={rec:.4f} f1={f1:.4f}")

    model = {
        "type": "logistic_regression",
        "version": "3.0.0",
        "coef": [w.tolist()],
        "intercept": [float(b)],
        "scaler_mean": mean.tolist(),
        "scaler_scale": scale.tolist(),
        "feature_names": FEATURE_NAMES,
        "n_features": len(FEATURE_NAMES),
        "threshold": 0.5,
    }
    with open(os.path.join(ASSETS, "malware_detector_v3.json"), "w") as f:
        json.dump(model, f)
    meta = {
        "version": "3.0.0",
        "model_file": "malware_detector_v3.json",
        "type": "logistic_regression",
        "features": len(FEATURE_NAMES),
        "threshold": 0.5,
        "accuracy": None, "f1_score": f1, "precision": prec, "recall": rec,
        "roc_auc": float(a),
        "trained_on": "MH-100K (real, MalHunt-100k)",
        "train_samples": int(len(tr)), "test_samples": int(len(te)),
        "feature_names": FEATURE_NAMES,
        "note": "38 维(15 权限组合+3 上下文+20 API 类别)；顺序与 MlScanner.extractFeatures 一致",
    }
    with open(os.path.join(ASSETS, "malware_detector_v3_metadata.json"), "w") as f:
        json.dump(meta, f, indent=2)
    print(f"导出: malware_detector_v3.json / _metadata.json  用时 {time.time()-t0:.1f}s")


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""
把 XGBoost 模型 (ml/models/mh1m/model.json, 扁平并行数组) 转成 app 端
MlScanner.parseXGBoostModel 期望的嵌套 schema:
  {"trees":[ {"leaf":f} | {"split_feature":i,"split_condition":c,"left":{...},"right":{...}} ]}
并校验转换前后预测逐样本完全等价。

输出: app/src/main/assets/model_mh1m.json
"""
import json, os, sys
import numpy as np

sys.setrecursionlimit(1000000)
ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
SRC = os.path.join(ROOT, "ml", "models", "mh1m", "model.json")
DST = os.path.join(ROOT, "app", "src", "main", "assets", "model_mh1m.json")
SCALER = os.path.join(ROOT, "app", "src", "main", "assets", "scaler_mh1m.json")


def conv_tree(t):
    lc, rc, si, sc, bw = t["left_children"], t["right_children"], t["split_indices"], t["split_conditions"], t["base_weights"]

    def node(i):
        if lc[i] == -1:
            return {"leaf": float(bw[i])}
        return {
            "split_feature": int(si[i]),
            "split_condition": float(sc[i]),
            "left": node(lc[i]),
            "right": node(rc[i]),
        }

    return node(0)


def pred_flat(x, trees):
    s = 0.0
    for t in trees:
        lc = t["left_children"]
        n = 0
        while lc[n] != -1:
            n = lc[n] if x[t["split_indices"][n]] < t["split_conditions"][n] else t["right_children"][n]
        s += t["base_weights"][n]
    return s


def pred_nested(x, roots):
    s = 0.0
    for r in roots:
        n = r
        while "leaf" not in n:
            n = n["left"] if x[n["split_feature"]] < n["split_condition"] else n["right"]
        s += n["leaf"]
    return s


def main():
    m = json.load(open(SRC))
    trees = m["learner"]["gradient_booster"]["model"]["trees"]
    print(f"原始树数: {len(trees)}")
    roots = [conv_tree(t) for t in trees]

    # ---- 等价性校验 ----
    scaler = json.load(open(SCALER))
    mean = np.array(scaler["mean"], dtype=np.float64)
    std = np.array(scaler["std"], dtype=np.float64)
    rng = np.random.RandomState(0)
    max_err = 0.0
    for _ in range(200):
        raw = rng.randint(0, 2, size=43).astype(np.float64)
        x = (raw - mean) / std
        e = abs(pred_flat(x, trees) - pred_nested(x, roots))
        max_err = max(max_err, e)
    print(f"等价性校验(200 随机样本) 最大误差 = {max_err:.3e}")

    json.dump({"trees": roots}, open(DST, "w"))
    sz = os.path.getsize(DST)
    print(f"导出: {DST} ({sz/1024/1024:.2f} MB)")
    if max_err > 1e-9:
        print("!! 转换不等价，请检查")
        sys.exit(1)
    print("转换等价 ✔")


if __name__ == "__main__":
    main()

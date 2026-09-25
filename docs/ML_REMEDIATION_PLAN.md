# Polaris ML 检测子系统修复方案

> 背景：审计发现内置 ML「能跑但无效」——mh1m 加载不了、mh100k 是合成数据训练、特征顺序错位。
> 本文给出可落地的分阶段修复步骤。**仅方案，代码未改动。**

## 一、问题清单（审计结论）

| # | 问题 | 位置 | 影响 |
|---|------|------|------|
| P1 | XGBoost(mh1m) 资产是 UBJSON 二进制，`new JSONObject()` 解析必失败 → 静默回退 LR | `assets/model_mh1m.xgb`、`MlScanner.parseXGBoostModel` | 选 mh1m 实际用 LR |
| P2 | 即便换 JSON，标准 dump schema(`learner→gradient_booster→model→trees`，`yes/no/children`) 与解析器期待的顶层 `"trees"`+`split_feature/left/right/leaf` 不符 | `MlScanner.java:350-395` | 同上 |
| P3 | 特征空间不同：mh1m 要 20 权限+8 Intent+5 opcode+10 APICall；app 产出 15 组合+8 上下文+20 API 类别 | `features_mh1m.json` vs `MlScanner.FEATURE_NAMES` | 喂错特征 |
| P4 | LR(mh100k) 训练于**合成随机数据** | `ml/train/train_v2_simple.py`、`malware_detector_v2_metadata.json` | 真实数据 AUC≈0.42（比随机差） |
| P5 | features[0-14] 用 Java **HashMap 桶顺序**填充，与训练/`FEATURE_NAMES` 声明顺序不一致 | `MlScanner.extractFeatures`、`PERMISSION_COMBOS`(HashMap) | 静默错位 |
| P6 | `DexParser` 仅 `containsString` 扫字面串，不解析 opcode/intent | `DexParser.java` | 无法支撑 mh1m 特征集 |

## 二、决策点：选一条特征管线（二选一，别维护两套）

- **路径 A（理想·工作量大）**：采用 mh1m 的真实特征集（权限/Intent/opcode/APICall）。需要新写 DEX 字节码解析（opcode）与 Intent 提取；且 `ml/` 无 mh1m 训练脚本，模型**不可复现**，迭代受限。
- **路径 B（推荐·务实）**：采用「app 现有可提取特征」的**精简集**（15 权限组合 + 3 上下文 + 20 API 类别），用**真实 MH-100K** 重训。复用现有提取逻辑，仅小改 app，训练/评估可复现。

> 建议：**先做路径 B 拿到一个真实可用的检测器**（阶段 1→2→5→6）；路径 A 作为后续升级（阶段 3→4）。

---

## 三、具体步骤

### 阶段 1：修特征顺序 bug（两条路径都要做）

**文件**：`app/src/main/java/com/polaris/app/scan/MlScanner.java`

1. 把 `PERMISSION_COMBOS` 由 `HashMap` 改为 `LinkedHashMap`，或直接改为有序数组：
   - `private static final String[] COMBO_NAMES = {"SMS_MALWARE","PHONE_MALWARE",...};`
   - `private static final String[][] COMBO_PERMS = {...};`
2. 使 `extractFeatures()` 的 `features[0..14]` 填充顺序 == `FEATURE_NAMES` 声明顺序（即训练顺序）。
3. `DexParser.API_CATEGORIES` 本是数组，顺序已固定，无需改（但要核对与训练侧同序）。
4. **验证**：加一条日志/脚本，逐项打印 `MlScanner.FEATURE_NAMES`，与训练脚本导出的 `feature_names` 严格相等。

### 阶段 2：用真实数据重训「app 管线」模型（路径 B 核心）

**训练数据**：从 `ml/data/raw/mh100.csv`（真实 MH-100K，101,934 行，标签在末列 `class`；分布 92,134 良性 / 9,800 恶意）派生特征：
- 15 个权限组合：来自 `Permission::*` 列（`READ_SMS` 等，全部出现才置 1）。
- 3 个可计算上下文：`permission_count`、`dangerous_perm_count`、`has_internet`（其余 5 个上下文特征 `isSystem/targetSdk/minSdk/hasNative/app_category` 在 CSV 中**无法还原**）。
- 20 个 API 类别：来自 `APICall::*` 列（smali 形式），按与 `DexParser.API_CATEGORIES` **同一套签名规则**匹配。
- **特征顺序固定**为阶段 1 定下的顺序。

**新增脚本**：`ml/train/train_real_pipeline.py`
- 产出 `app/src/main/assets/malware_detector_v3.json`（LR；`coef/intercept/scaler_mean/scaler_scale/feature_names/threshold`）。
- 同步 `malware_detector_v3_metadata.json`（`trained_on` 如实写 MH-100K，`feature_names` 逐项对应）。

**关键约束**：
- 训练特征集必须与运行时**逐项同序同名**。若保留那 5 个不可计算上下文特征 → 训练与运行时都置**同一常量**（会被模型忽略）；**更推荐直接从两边删掉，降到 38 维**。
- 需核对 `DexParser.extractApiCalls` 的匹配语义（它用 Java 点号名 `containsString` 扫 DEX 字节；DEX 内部是 smali 描述符 `Landroid/content/Intent;`）——若不一致，需统一规则，否则训练/推理对不上。

**命令**：
```bash
python ml/train/train_real_pipeline.py
./gradlew assembleDebug
```

### 阶段 3：修 mh1m 加载（若走路径 A）

**最小改动方案**：新增 `ml/train/xgb_to_java_json.py`，把标准 dump 转成 `MlScanner.parseXGBoostModel` **已支持的 schema**，从而**不改 Java**：
- 读 `ml/models/mh1m/model.json`（标准 dump，500 树，节点含 `split:"f12"` / `split_condition` / `yes` / `no` / `children` / `leaf`）。
- 输出 `{"trees":[ { "split_feature":12, "split_condition":..., "left":{...}, "right":{...} } | {"leaf":...} ]}`，递归展开 `children[yes]`→left、`children[no]`→right。
- 存为 `app/src/main/assets/model_mh1m.json`，并把 `MlScanner.XGB_MODEL_FILE` 改为 `model_mh1m.json`。
- 删除/停用二进制 `model_mh1m.xgb`。

**或彻底方案**：重写 `parseXGBoostModel` 直接吃标准 dump 嵌套结构（改动更大但更通用）。

### 阶段 4：重建 mh1m 的 43 维特征提取（仅路径 A）

**文件**：`app/src/main/java/com/polaris/app/scan/DexParser.java`
1. 权限（20）：`PackageInfo.requestedPermissions` → 就绪。
2. Intent（8）：新写 `extractIntents()`——扫 manifest/DEX 字符串（`android.intent.action.BOOT_COMPLETED` 等）。
3. **Opcode（5，工作量最大）**：新写 DEX 字节码解析，统计 `const-string / invoke-virtual / invoke-static / move-result-object / if-eqz` 的出现。
4. APICall（10）：复用 `extractApiCalls`，核对命名与 `features_mh1m.json` 一致。
5. 按 `features_mh1m.json` 顺序组装 43 维。

### 阶段 5：真实数据验证（脚手架已备）

**脚本**：`ml/eval/lr_real_eval.py`（零依赖，已可跑）→ 扩展为对 v3 / mh1m 打分：
```bash
python ml/eval/lr_real_eval.py 20000 5   # 跨文件采样 2 万，输出 ROC-AUC / PR-AUC / @阈值指标
```
**达标门槛（建议）**：ROC-AUC ≥ 0.95，且高召回（如 recall=0.9）下 precision 可接受，方可上线。

### 阶段 6：收尾与去留决策

1. 模型命名升版（v3），metadata 如实标注真实训练集与指标。
2. **只有阶段 5 证明某模型明显更优，才删另一个**；建议保留一个轻量模型作 fallback（加载异常时兜底），但必须是真实训练的。
3. 修正欢迎页/设置页「轻量/强力」文案，使其与实测一致。

---

## 四、推荐执行顺序

1. **阶段 1**（修顺序，半天）→ 2. **阶段 2**（真实数据重训 LR，1 天）→ 3. **阶段 5**（验证，确认达标）→ 4. **阶段 6**（升版/文案）→ 5.（可选）**阶段 3+4** 升级到真实数据 XGBoost。

## 五、风险与注意

- mh100.csv 缺失 5 个上下文特征，路径 B 只能覆盖 38/43 维；建议直接定为 38 维模型，避免训练/运行时分布不一致。
- `DexParser` 的 API 匹配语义（点号名 vs smali 描述符）必须与训练侧统一，否则线上线下特征对不上。
- `mh100.csv` 5GB，训练建议 `usecols` + 分块；评估用 `ml/eval/lr_real_eval.py` 的步进采样。
- 现有 `mh100.parquet`（99MB）列布局与 CSV 相同，可用于加速训练。

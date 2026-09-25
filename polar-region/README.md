# Polar Region · 威胁情报病毒库

Polar Region 是 Polaris Safety 的 IOC（Indicators of Compromise）清单，提供恶意样本特征用于本地匹配。

> **⚠ 仓库现状（2026-09-13）**：文件名已交换并对齐版本标识。最终约定——**Polar Region = v14 · 大（全量，~357 MB / 1,610,800 条）**；**Polar Point = v1 · 小（小全量，~325 MB）**。下文以磁盘现状为准。

## 与 Polar Point 的关系（以磁盘现状为准）

| 特性 | Polar Region (v14 · 大) | Polar Point (v1 · 小) |
|------|---------------------|---------------------|
| **定位** | 全量版 · 完整威胁情报 | 小全量 · 典型样本 |
| **条目数** | 1,610,800 条 | ~325 MB 全量子集 |
| **用途** | 全量主库 · 离线兜底（⚠ 含 ~357MB 全量，作 APK 种子需评估体积） | 小全量 · 轻量分发 / 深度扫描 |
| **文件大小** | ~357 MB | ~325 MB |
| **更新频率** | 手动维护 | 自动同步 |

## 数据规模

| 指标 | 数量 |
|------|------|
| **总条目数** | 1,610,800（全量，与 Polar Point 同源） |
| SHA-256 哈希 | 见 entries 字段 |
| 恶意包名 | 见 entries 字段 |
| 真实恶意家族 | 见 entries 字段 |

## 数据格式

`iodb.json` 结构：

```json
{
  "db_version": 14,
  "updated_at": "2026-09-12",
  "source": "Polar Point v1 Android-focused (1,610,800 -> 10,000)",
  "entries": [
    {
      "pkg": "com.example.malware",
      "sha256": "64位十六进制哈希",
      "family": "Joker",
      "type": "trojan",
      "severity": "critical",
      "desc": "样本描述",
      "tags": ["subscription", "billing_fraud"],
      "url": "https://malicious-site.com/payload.apk"
    }
  ]
}
```

## 字段说明

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `pkg` | string | 应用包名（重打包 / 改名兜底匹配） |
| `sha256` | string | 样本 SHA-256（优先精确匹配） |
| `family` | string | 恶意家族名（如 Joker / Anubis / Cerberus） |
| `type` | string | trojan / spyware / ransomware / adware / phishing / riskware / miner |
| `severity` | string | low / medium / high / critical |
| `desc` | string | 中文描述 |
| `tags` | string[] | 行为标签 |
| `url` | string | 恶意分发 URL（来自 URLhaus 等数据源） |

## 典型恶意家族

| 家族 | 类型 | 严重度 | 描述 |
|------|------|--------|------|
| AgentTesla | trojan | high | 窃密木马 |
| AsyncRAT | trojan | high | 远控木马 |
| XWorm | trojan | high | 远控木马 |
| Mirai | malware | high | IoT 僵尸网络 |
| Formbook | trojan | high | 窃密木马 |
| RemcosRAT | trojan | high | 远控木马 |
| QuasarRAT | trojan | high | 远控木马 |
| VenomRAT | trojan | high | 远控木马 |

## 分发

应用从以下地址拉取更新（可替换为你的 fork 地址）：

```
https://raw.githubusercontent.com/cat-and-mouse1/Polaris_Safety/main/polar-region/iodb.json
```

## 维护

### 本地生成数据库

```bash
python3 filter_typical.py
# 从 Polar Point 筛选 10,000 条典型样本
```

### 提交新情报（接受 PR）

> **Polar Region 接受社区 PR**：新增样本、修正家族标注均欢迎。

1. 编辑 `data/` 下对应数据源，或直接补充条目
2. 重新生成：`python3 build_ioc.py`
3. 提交 PR，说明样本来源与恶意行为佐证
4. 合并后由筛选管线自动同步到 Polar Point（Point 不接受 PR）

## 许可证

与主项目一致：[Apache License 2.0](../LICENSE)。

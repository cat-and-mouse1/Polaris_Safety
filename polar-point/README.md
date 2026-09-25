# Polar Point · 典型常见威胁过滤库（从 Region v14 筛选）

Polar Point 是 Polaris Safety 的 IOC（Indicators of Compromise）数据库，提供典型恶意样本特征。

> **⚠ 仓库现状（2026-09-13）**：文件名已交换并对齐版本标识。最终约定——**Polar Point = v1 · 小（从 Region v14 筛选的命名家族集，74,771 条 / ~14.3 MB，db_version:1）**；**Polar Region = v14 · 大（全量，~357 MB / 1,610,800 条）**。point 已剔除 1,536,029 条泛化 `Local Hash Database` 原始哈希，仅保留命名家族（真实恶意家族 + 已知黑名单）。

## 与 Polar Region 的关系（以磁盘现状为准）

| 特性 | Polar Region (v14 · 大) | Polar Point (v1 · 小) |
|------|---------------------|---------------------|
| **定位** | 全量版 · 完整威胁情报 | 过滤子集 · 命名家族典型威胁 |
| **条目数** | 1,610,800 条 | 74,771 条（命名家族过滤） |
| **用途** | 全量主库 · 离线兜底（⚠ 含 ~357MB 全量，作 APK 种子需评估体积） | 典型常见威胁 · 轻量分发 / 深度扫描 |
| **文件大小** | ~357 MB | ~14.3 MB |
| **更新频率** | 手动维护 | 自动同步 |

## 数据规模

| 指标 | 数量 |
|------|------|
| **总条目数** | 74,771 条（命名家族过滤，~14.3 MB，db_version:1） |
| 数据源 | 见 source 字段 |

## 数据格式

`iodb.json` 结构：

```json
{
  "db_version": 1,
  "updated_at": "2026-09-12",
  "source": "abuse.ch MalwareBazaar + abuse.ch URLhaus + TweetFeed + romainmarcoux/malicious-hash + DataDog + pypi_malregistry + Polar Region",
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

## 数据源

| 数据源 | 数据量 | 说明 | 许可 |
|--------|--------|------|------|
| **abuse.ch MalwareBazaar** | 10,000+ | 恶意软件样本哈希 | 公开 |
| **abuse.ch URLhaus** | 10,000+ | 恶意软件分发 URL 和关联哈希 | 公开 |
| **TweetFeed** | 1,000+ | Twitter/X 研究员分享的恶意哈希 | CC0 1.0 |
| **romainmarcoux/malicious-hash** | 10,751 | 聚合多源 SHA-256 哈希 | MIT |
| **DataDog 恶意包数据集** | 28,623+ | PyPI/npm/IDE 恶意包 | Apache-2.0 |
| **lxyeternal/pypi_malregistry** | 10,823+ | PyPI 恶意包清单 | MIT |
| **Polar Region 社区** | 100+ | 本地种子库（回退兜底） | Apache-2.0 |

## 分发

应用从以下地址拉取更新：

```
https://raw.githubusercontent.com/cat-and-mouse1/Polaris_Safety/main/polar-point/iodb.json
```

## 维护

### 本地生成数据库

```bash
python3 build_ioc.py
# 产出 iodb.json（约 1,610,800 条目）
```

### 关于 PR（不接受 PR）

> **Polar Point 不接受 PR**：本库由 Polar Region 经筛选管线自动生成，请勿直接修改。

如需提交新情报，请向 [Polar Region](../polar-region/) 提交 PR；Region 合并后由筛选管线自动同步到本库。

## 许可证

与主项目一致：[Apache License 2.0](../LICENSE)。

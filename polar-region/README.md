# Polar Region · 开源 Android 威胁情报病毒库

Polar Region 是 Polaris Safety 使用的开源 IOC（Indicators of Compromise）清单，为应用提供云端可更新的恶意样本特征。数据库名取自北极星（Polaris）所在的极地天区。

## 数据格式

`iodb.json` 结构：

```json
{
  "db_version": 12,
  "updated_at": "2026-08-27",
  "source": "abuse.ch MalwareBazaar + Polar Region 社区维护清单",
  "entries": [
    {
      "pkg": "com.example.malware",
      "sha256": "64位十六进制哈希",
      "family": "Joker",
      "type": "trojan",
      "severity": "high",
      "desc": "样本描述",
      "tags": ["subscription", "billing_fraud"]
    }
  ]
}
```

字段说明：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `pkg` | string | 应用包名（重打包 / 改名兜底匹配） |
| `sha256` | string | 样本 SHA-256（优先精确匹配） |
| `family` | string | 恶意家族名（如 Joker / Anubis / Cerberus） |
| `type` | string | trojan / spyware / ransomware / adware / phishing / riskware / miner |
| `severity` | string | low / medium / high / critical |
| `desc` | string | 中文描述 |
| `tags` | string[] | 行为标签 |

## 数据源

- **abuse.ch MalwareBazaar**：公开恶意样本库，应用运行时自动拉取真实哈希
- **社区维护清单**：本仓库 `iodb.json`，托管于 GitHub raw，作为种子库与兜底

## 分发

应用从以下地址拉取更新（可替换为你的 fork 地址）：

```
https://raw.githubusercontent.com/cat-and-mouse1/Polaris_Safety/main/polar-region/iodb.json
```

可选签名文件（HMAC-SHA256，上线前配置密钥后启用校验）：

```
https://raw.githubusercontent.com/cat-and-mouse1/Polaris_Safety/main/polar-region/iodb.json.sig
```

## 维护

### 本地生成种子库

```bash
python3 build_ioc.py
# 产出 iodb.json
```

`build_ioc.py` 内置初版种子（62 条），sha256 为确定性占位值；真实哈希由 abuse.ch 拉取后刷新。

### 提交新情报

1. 在 `build_ioc.py` 的 `SEED` 列表（或直接编辑 `iodb.json`）新增条目
2. 重新生成：`python3 build_ioc.py`
3. 提交 PR，说明样本来源与恶意行为佐证

## 许可证

与主项目一致：[Apache License 2.0](../LICENSE)。

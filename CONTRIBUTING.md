# 贡献指南 · Contributing

感谢你为 Polaris Safety 贡献力量！无论是提 Issue、修复 bug、补充威胁情报还是完善文档，都欢迎。

## 行为准则

- 尊重他人，就事论事，不人身攻击。
- 不提交任何恶意代码、后门或侵犯他人隐私的内容。
- 遵守你所在地区的法律法规。

## 如何贡献

### 1. 提交 Issue

报告 bug 或提建议时，请尽量包含：

- 复现步骤与预期 / 实际行为
- 设备型号、Android 版本、使用的守护模式
- 相关日志（去掉敏感信息，如 API Key）

### 2. 提交 Pull Request

1. Fork 本仓库并创建分支：`git checkout -b feat/xxx`
2. 修改代码，保持与现有风格一致（4 空格缩进、中文注释）
3. 本地构建验证：`./gradlew assembleDebug`
4. 提交并推送，发起 PR，说明改动动机与影响面

### 3. 补充 Polar Region 威胁情报

IOC 病毒库采用独立维护，提交方式见 [polar-region/README.md](polar-region/README.md)。

提交情报时请遵循：

- **真实可查**：标注样本来源（如 MalwareBazaar SHA-256、VT 链接或公开披露报告）
- **哈希优先**：优先提供真实 SHA-256；无真实哈希时仅提供包名并注明
- **不夹带误报**：对知名厂商 / 正常应用勿凭名称猜测，需有明确恶意行为佐证

## 代码规范

- 语言：Java 17
- 布局 / 资源：Android XML，字符串统一进 `res/values*/strings.xml`（zh / en / ja 三套同步）
- 网络：保持零第三方依赖，优先原生 `HttpURLConnection`
- 高危操作（删除 / 冻结 / 卸载）前必须有二次确认或明确提示

## 安全提醒

- API Key 等敏感信息必须使用 Keystore 加密存储，禁止明文落盘或打印到日志。
- 提交前自查：不要提交 `local.properties`、`.gradle/`、`build/` 等本地 / 生成物。

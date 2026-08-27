# Polaris Safety · 北极星手机安全守护

面向 Android 的开源手机安全守护应用：四级守护模式 + AI 智能判定引擎 + 文件扫描中心 + 红色「拦截」守护栏 + 开源威胁情报病毒库 **Polar Region**。

> ⚠️ 本项目仅用于安全研究与个人设备防护，不能替代专业杀毒软件。高级拦截功能（Shizuku / Root）需要用户主动授权，请仅在理解风险的前提下使用。

## 功能特性

- **四级守护模式**：Normal（浅层扫描）/ Accessibility（深层扫描）/ Shizuku（拦截守护）/ Root（全方位守护）
- **AI 智能判定引擎**：接入大模型 API，对扫描结果做「清除 / 保留」二次判定，默认 DeepSeek，支持 7 家服务商
- **扫描中心**：文件夹定向扫描 + 全盘扫描；隔离区（隔离 / 恢复 / 放行）与放行名单管理
- **红色「拦截」守护栏**：实时拦截恶意锁机 / 霸屏 / 盗取通知 / 勒索 / 侵犯使用权的应用；开启后通知栏置顶显示「拦截模式已开启」，可一键关闭；被拦截应用集中管理（放行 / 删除）
- **Polar Region 病毒库**：开源 IOC 威胁情报（包名 / SHA-256 / 家族 / 严重度），数据源为 abuse.ch MalwareBazaar + 社区维护清单，与内置签名库、AI 判定三路融合
- **多语言**：简体中文 / English / 日本語（应用内切换，全局生效）
- **安全细节**：API Key 使用 Android Keystore + AES/GCM 加密落盘；全程零第三方网络依赖（原生 HttpURLConnection）

## 目录结构

```
Polaris/
├── app/                    # Android 应用源码
│   └── src/main/
│       ├── java/com/polaris/app/   # Activity / Service / 扫描引擎
│       ├── res/                    # 布局、字符串（values / values-en / values-ja）、资源
│       └── assets/iodb_seed.json   # Polar Region 病毒库种子
├── polar-region/           # Polar Region IOC 病毒库（独立维护）
│   ├── iodb.json           # IOC 清单（可托管于 GitHub raw 分发）
│   └── build_ioc.py        # 种子库生成脚本
├── gradle/                 # Gradle wrapper
└── build.gradle            # 构建脚本
```

## 构建

环境要求：

- JDK 17
- Android SDK（compileSdk 37，build-tools 36）
- Gradle 9.5（项目自带 wrapper，首次构建自动下载）

```bash
# 配置本地 SDK 路径（若尚未配置）
# echo "sdk.dir=/path/to/Android/Sdk" > local.properties

./gradlew assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

## Polar Region 病毒库

Polar Region 是一个开源 Android 威胁情报（IOC）清单，用于给 Polaris Safety 提供云端可更新的恶意样本特征。

- **字段**：`pkg`（包名）、`sha256`（样本哈希）、`family`（恶意家族）、`type`（类型）、`severity`（严重度）、`desc`、`tags`
- **数据源**：abuse.ch MalwareBazaar 自动拉取 + 社区维护清单（GitHub raw 托管）
- **匹配优先级**：SHA-256 精确匹配 → 包名匹配（重打包 / 改名兜底）
- **分发地址**：`https://raw.githubusercontent.com/cat-and-mouse1/Polaris_Safety/main/polar-region/iodb.json`

详见 [polar-region/README.md](polar-region/README.md)。

## 许可证

本项目采用 [Apache License 2.0](LICENSE)。

## 免责声明

本项目提供的威胁情报与检测能力仅供参考。因误报、漏报或使用高级权限（Root / Shizuku）导致的数据丢失或设备异常，作者不承担任何责任。请在使用「删除」「冻结」等高危操作前自行确认。

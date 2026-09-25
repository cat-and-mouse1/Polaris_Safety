# 安装 Android Studio

## 自动安装失败，需要手动安装

### 步骤 1: 下载 Android Studio

访问: https://developer.android.com/studio

下载最新版本 (约 1GB)

### 步骤 2: 运行安装程序

1. 双击下载的 `android-studio-*.exe`
2. 按照安装向导操作
3. 选择默认安装选项
4. 安装完成后启动 Android Studio

### 步骤 3: 首次启动设置

1. 选择 "Standard" 安装类型
2. 等待 SDK 下载完成
3. 完成设置向导

### 步骤 4: 打开项目

1. 点击 "Open an Existing Project"
2. 导航到: `E:\Personal\操作\AI\Output\Polaris_GitHub_v2.1.0`
3. 点击 "OK"
4. 等待 Gradle 同步完成

### 步骤 5: 构建 APK

1. 点击菜单 Build > Build Bundle(s) / APK(s) > Build APK(s)
2. 等待构建完成
3. APK 将生成在: `app/build/outputs/apk/debug/app-debug.apk`

## 验证 ML 集成

构建成功后，ML 检测引擎将自动:
- 加载 `malware_detector.json` (91.77% 准确率)
- 提取 24 个特征
- 使用 Logistic Regression 分类
- 在 UI 显示 ML 分数徽章

## 替代方案: 仅使用命令行工具

如果不想安装 Android Studio，可以:

1. 下载命令行工具:
   https://developer.android.com/studio#command-line-tools-only

2. 解压到 `C:\Android\Sdk\cmdline-tools\latest`

3. 设置环境变量:
   ```
   ANDROID_HOME=C:\Android\Sdk
   ```

4. 安装 SDK:
   ```bash
   sdkmanager "platform-tools" "platforms;android-34" "build-tools;34.0.0"
   ```

5. 构建:
   ```bash
   .\gradlew.bat assembleDebug
   ```

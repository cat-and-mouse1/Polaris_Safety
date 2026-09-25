# Android SDK 设置指南

## 当前状态
- ML 模型已训练完成 (准确率: 91.77%)
- 模型已复制到 `app/src/main/assets/malware_detector.json`
- MlScanner.java 已修改为使用 JSON 模型 (无需 TFLite)

## 设置 Android SDK

### 方法 1: 使用 Android Studio (推荐)

1. 下载 Android Studio: https://developer.android.com/studio
2. 安装后打开 Android Studio
3. 进入 Settings > Languages & Frameworks > Android SDK
4. 记下 SDK 路径 (默认: `C:\Users\Administrator\AppData\Local\Android\Sdk`)

### 方法 2: 手动安装命令行工具

1. 下载命令行工具:
   https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip

2. 解压到 `C:\Android\Sdk\cmdline-tools\latest\bin`

3. 设置环境变量:
   ```
   ANDROID_HOME=C:\Android\Sdk
   PATH=%ANDROID_HOME%\cmdline-tools\latest\bin;%PATH%
   ```

4. 安装必要组件:
   ```bash
   sdkmanager "platform-tools" "platforms;android-34" "build-tools;34.0.0"
   ```

## 构建项目

设置好 SDK 后，运行:
```bash
.\gradlew.bat assembleDebug
```

## 验证 ML 集成

构建成功后，ML 检测引擎将自动:
1. 在扫描时加载 `malware_detector.json`
2. 提取 24 个特征 (权限 + API 调用)
3. 使用 Logistic Regression 模型分类
4. 将 ML 分数作为 30% 权重加入总分
5. 在 UI 中显示 ML 分数徽章

## 模型信息

- **算法**: Logistic Regression
- **特征**: 24 (权限 + API 调用)
- **训练样本**: 101,934 个 Android 应用
- **准确率**: 91.77%
- **模型大小**: 3.3 KB
- **格式**: JSON (纯 Java 实现，无需 TFLite)

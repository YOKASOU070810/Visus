# Visus

Visus 是一个面向视力障碍用户的 Android 辅助出行应用，配套 FastAPI 后端提供登录注册、好友状态、紧急求助、地图导航、主界面 AI 助手、摄像头/语音推流与辅助出行播报能力。

当前仓库保留的是正在使用的主线代码：

- Android 客户端：`app/android`
- Python 后端：`server`
- 后端启动脚本：`start_backend.ps1`、`server/start_backend.ps1`

旧版 Django/Java Demo、历史占位目录、旧 APK、红绿灯 YOLO 专用检测模块等冗余内容已清理。

## 功能概览

- 登录 / 注册：用户身份保存到本地，后端使用 JWT 认证。
- 主界面 AI：支持文字输入和系统语音识别，调用后端 AI Agent 理解用户意图。
- AI 动作联动：
  - “开启辅助出行”会切换到辅助出行页并启动推流。
  - “去医院 / 导航到人民广场”会切换到地图导航页并自动规划路线。
  - “查看好友状态”会切换到好友页。
  - “紧急求助”会触发 SOS 通知。
- 辅助出行：手机端上传摄像头和麦克风数据，后端返回 AI 语音播报、画面预览和导航提示。
- 好友与提醒：好友列表、好友状态、SOS 弹窗、后台通知。
- 地图导航：通过高德接口做目的地搜索、路径规划和语音摘要。

## 项目结构

```text
Visus/
├─ app/android/                  # Android 主应用，Kotlin + Jetpack Compose
│  ├─ app/src/main/java/com/visus/app/
│  │  ├─ ui/screens/             # 主要页面：AI、导航、好友、提醒、我的
│  │  ├─ network/                # HTTP / WebSocket API 客户端
│  │  ├─ service/                # 推流、后台通知、位置上报服务
│  │  └─ data/                   # 登录状态、设置、全局状态
│  └─ app/build/outputs/apk/     # Gradle 生成的 APK 输出目录
├─ server/
│  ├─ src/core/app_main.py       # 完整后端入口
│  ├─ src/social/                # 登录、好友、消息、地图、AI Agent API
│  ├─ src/voice/                 # ASR、LLM、TTS、音频流
│  ├─ src/navigation/            # 辅助出行/过马路相关流程
│  ├─ src/vision/                # 寻物和障碍物相关视觉能力
│  ├─ config/requirements.txt    # Python 依赖
│  ├─ .env.example               # 环境变量模板
│  └─ start_backend.ps1          # 后端启动脚本
├─ start_backend.ps1             # 根目录快捷启动脚本
└─ README.md
```

## 环境要求

### 后端

- Windows 10/11
- Python 3.9 到 3.11，推荐 Python 3.11
- PowerShell
- 可访问豆包方舟、高德地图、DashScope 的网络环境

后端主要依赖：

- FastAPI / Uvicorn
- SQLAlchemy / PyJWT
- OpenAI SDK，用于调用火山方舟兼容接口
- DashScope，用于实时 ASR
- Volcengine TTS
- OpenCV / MediaPipe / Ultralytics / PyTorch，用于辅助出行视觉能力

### Android

- Android Studio 或本仓库自带 Gradle Wrapper
- JDK 17
- Android SDK 35
- 手机 Android 8.0 及以上，推荐 Android 11 及以上
- 手机和电脑连接同一个局域网

## 后端配置

1. 进入项目根目录：

```powershell
cd E:\newvisusmain\Visus
```

2. 如果还没有 `server\.env`，复制模板：

```powershell
Copy-Item server\.env.example server\.env
```

3. 编辑 `server\.env`，填写真实密钥：

```env
ARK_API_KEY=你的火山方舟API Key
ARK_MODEL=你的方舟模型/endpoint id
ARK_BASE_URL=https://ark.cn-beijing.volces.com/api/v3

VOLCENGINE_TTS_APP_ID=你的火山TTS App ID
VOLCENGINE_TTS_ACCESS_TOKEN=你的火山TTS Access Token
VOLCENGINE_TTS_CLUSTER=volcano_tts
VOLCENGINE_TTS_VOICE_TYPE=BV700_V2_streaming
VOLCENGINE_TTS_ENCODING=wav

DASHSCOPE_API_KEY=你的DashScope实时语音识别Key
```

4. 首次安装依赖：

```powershell
powershell -ExecutionPolicy Bypass -File server\start_backend.ps1 -InstallDeps
```

5. 启动后端：

```powershell
powershell -ExecutionPolicy Bypass -File server\start_backend.ps1
```

启动成功后会看到：

```text
[INFO] Backend URL: http://localhost:8081
[INFO] Starting Visus backend...
```

后端默认监听：

```text
http://0.0.0.0:8081
```

手机访问时不要填 `localhost`，要填电脑在局域网中的 IP，例如：

```text
192.168.1.23:8081
```

## 获取电脑局域网 IP

在 PowerShell 执行：

```powershell
ipconfig
```

找到当前 Wi-Fi 或以太网下的 IPv4 地址，例如：

```text
IPv4 地址 . . . . . . . . . . . . : 192.168.1.23
```

Android App 里服务器地址填：

```text
IP: 192.168.1.23
Port: 8081
```

## Android 构建 APK

在项目根目录执行：

```powershell
cd app\android
.\gradlew.bat :app:assembleDebug
```

构建成功后 APK 位于：

```text
E:\newvisusmain\Visus\app\android\app\build\outputs\apk\debug\app-debug.apk
```

仓库内也提供了一份可直接安装的 Debug APK：

```text
release/Visus-v1.0.0-debug.apk
```

## 手机安装 APK

### 方法一：直接复制安装

1. 把 `release/Visus-v1.0.0-debug.apk` 复制到 Android 手机。
2. 在手机文件管理器中点击 APK。
3. 如果提示“禁止安装未知来源应用”，进入系统设置允许当前文件管理器安装未知应用。
4. 安装完成后打开 Visus。

### 方法二：使用 ADB 安装

1. 手机开启开发者模式和 USB 调试。
2. 用 USB 连接电脑。
3. 在项目根目录执行：

```powershell
adb install -r release\Visus-v1.0.0-debug.apk
```

如果提示设备未授权，请在手机上确认 USB 调试授权。

## App 使用流程

1. 启动后端。
2. 安装并打开 Android App。
3. 注册或登录账号。
4. 进入“我的”或导航页设置服务器 IP 和端口。
5. 主界面 AI：
   - 可以输入“你好”“附近医院”“开启辅助出行”“我在哪”“紧急求助”。
   - 语音按钮依赖手机系统语音识别服务；如果手机不支持，会自动显示文字输入。
6. 辅助出行：
   - 点击“导航”底部栏。
   - 在“辅助出行”页点击“开始辅助出行”。
   - 手机会推送摄像头和麦克风到后端，后端返回画面和语音播报。
7. 地图导航：
   - 点击“地图导航”。
   - 输入目的地或通过 AI 说“去附近医院”。
   - App 会规划路线并播报导航摘要。
8. 好友与提醒：
   - 添加好友后可以查看好友状态。
   - 触发 SOS 后，好友端会收到提醒和弹窗。

## 常见问题

### AI 显示 Read timed out

主界面 AI 请求已经使用较长超时。如果仍超时，通常是后端无法访问方舟模型或模型响应过慢。请检查：

- `server\.env` 中 `ARK_API_KEY` 和 `ARK_MODEL` 是否正确。
- 电脑网络是否能访问火山方舟。
- 后端终端是否有 `[AI ERROR]` 或 HTTP 错误日志。

### 语音识别不可用

主界面 AI 使用 Android 系统 `SpeechRecognizer`。如果手机系统没有可用语音识别服务，App 会显示文字输入。解决办法：

- 确认手机系统语音输入服务已启用。
- 安装或启用系统自带语音识别服务。
- 继续使用文字输入，不影响 AI 调用。

### 手机连不上后端

请检查：

- 手机和电脑是否在同一个 Wi-Fi。
- App 里服务器 IP 是否是电脑局域网 IP，而不是 `localhost`。
- Windows 防火墙是否放行 Python/8081 端口。
- 后端是否正在运行。

### 注册登录后闪退

通常是权限或后台服务启动失败导致。当前版本已对后台服务启动做了异常保护。仍出现时请检查：

- 是否授予相机、麦克风、通知、定位权限。
- Android 系统是否限制后台服务。
- 重新安装最新构建的 APK。

## 清理说明

本次清理删除了不属于当前主线的内容：

- 旧版 Django + Java Android Demo：`alert_app`
- 空的历史占位文件：`ai_assist`、`alert-buddy`、`alert_app_local_backup`、`android-app/android-app`、`Visus`
- 旧 APK：`app/android/Visus-demo.apk`
- 红绿灯 YOLO 专用检测模块：`server/src/navigation/trafficlight_detection.py`
- 后端启动时的红绿灯模型预加载和相关语音命令入口

保留内容：

- 当前 Kotlin Compose Android App
- FastAPI 后端
- 主界面 AI
- 地图导航
- 好友/提醒/SOS
- 辅助出行推流
- 寻物和障碍物相关视觉代码

## 开发常用命令

后端语法检查：

```powershell
server\.venv\Scripts\python.exe -m py_compile server\src\core\app_main.py server\src\social\ai_agent.py
```

Android Debug 构建：

```powershell
cd app\android
.\gradlew.bat :app:assembleDebug
```

查看 Git 状态：

```powershell
git status -sb
```

# AI辅助出行 - 开发记录

## 项目概述

将开源项目 OpenAIGlasses_for_Navigation 改造为可在 Android 手机上运行的 AI 辅助出行 App（客户端-服务端架构），修复多项 Bug，完善全部 AI 功能。

**改造日期**: 2026-05-16 ~ 2026-05-17

---

## 一、新增：Android 客户端 App

### 技术栈
- Kotlin + Jetpack Compose (UI)
- CameraX (摄像头采集)
- OkHttp WebSocket (服务端通信)
- AudioRecord / AudioTrack (音频采集与播放)

### 项目位置
`android_app/`

### 核心文件

| 文件 | 功能 |
|---|---|
| `MainActivity.kt` | 主入口，协调摄像头/音频/WebSocket |
| `camera/CameraManager.kt` | CameraX 采集 YUV→JPEG，10fps |
| `network/WebSocketManager.kt` | 4路 WebSocket 连接管理，自动重连 |
| `audio/AudioManagers.kt` | 录音 PCM 16k→服务器 / 播放服务器 8k 语音流 |
| `ui/MainScreen.kt` | Compose UI：视频面板、ASR 状态、AI 对话气泡、设置弹窗 |
| `ui/Theme.kt` | 暗色主题配色 |

### App 与服务器通信

```
手机 CameraX ──JPEG──► /ws/camera ──► Python 服务器
手机 AudioRecord ──PCM 16k──► /ws_audio ──► 阿里云 ASR
服务器 ──JPEG──► /ws/viewer ──► 手机屏幕显示
服务器 ──JSON──► /ws_ui ──► 手机显示识别结果/AI回复
服务器 ──PCM 8k WAV──► /stream.wav ──► 手机扬声器播放
```

### App 功能
- 实时显示服务器处理后的视频流
- 语音识别结果实时显示
- AI 对话气泡界面
- 服务器地址配置（齿轮图标）
- 4 路连接状态指示灯 (CAM/MIC/VID/UI)
- 摄像头始终保持运行，断连不影响画面

---

## 二、Python 服务端修复

### 1. Python 3.13 兼容性
- **问题**: Python 3.13 移除了 `audioop` 模块（PEP 594）
- **修复**: 创建 `audioop_shim.py` 替代实现（mul/tomono/ratecv）
- **关键**: 必须 `sys.modules['audioop'] = audioop` 注册到全局，否则函数内部 `import audioop` 找不到
- **影响文件**: `app_main.py`, `audio_compressor.py`, `audio_player.py`

### 2. MediaPipe 0.10.35 兼容性
- **问题**: 新版 mediapipe 移除了 `mediapipe.framework.formats` 和 `mediapipe.solutions`
- **修复**: `yolomedia.py` 重写手部绘制，硬编码骨骼连接关系，移除 protobuf 依赖
- **影响功能**: 物品搜索（yolomedia）

### 3. 硬编码路径修复
- **问题**: 7 处模型路径写死为 `C:\Users\Administrator\Desktop\rebuild1002\...`
- **修复**: 全部改为项目相对路径或 `os.path.join()`
- **影响文件**: `app_main.py`, `audio_player.py`, `trafficlight_detection.py`, `yolomedia.py`, `yoloe_backend.py`

### 4. 服务器启动崩溃修复
- **问题**: YOLOE 模型加载时尝试下载 572MB CLIP 权重，GitHub 不通导致 `ConnectionError` 崩溃
- **修复**: `obstacle_detector_client.py` 将 `raise` 改为 `self.model = None`，降级运行
- **临时方案**: 添加 `SKIP_OBSTACLE_DETECTOR=1` 环境变量跳过障碍物检测器加载

### 5. ASR 语音识别激活
- **问题**: Android 端从未发送 `START` 命令，服务器 ASR 未启动
- **修复**: Audio WebSocket 连接成功后自动发送 `START`

### 6. AI 语音输出修复
- **问题**: `/stream.wav` 新客户端连接时强制踢掉所有旧客户端
- **修复**: 改为按 10 秒无活动超时踢掉，支持多客户端共存
- **影响**: Android App 的音频流不再被意外中断

### 7. 摄像头重连修复
- **问题**: 断连后重连，CameraX 未解绑导致摄像头打不开
- **修复**: 重构为摄像头始终运行，断连只停 WebSocket 和音频

### 8. WebSocket 重连逻辑修复
- **问题**: 4 条连接共享 1 个重连计数器，断连后旧重连协程未取消
- **修复**: 每连接独立计数，断连取消所有重连任务，连上归零

---

## 三、模型文件

从魔搭社区下载 5 个模型文件到 `model/` 目录：

| 文件 | 大小 | 用途 |
|---|---|---|
| yolo-seg.pt | 138MB | 盲道分割 (2类: road_crossing, blind_path) |
| yoloe-11l-seg.pt | 68MB | 开放词汇障碍物检测 |
| shoppingbest5.pt | 138MB | 物品识别 |
| trafficlight.pt | 167MB | 红绿灯检测 |
| hand_landmarker.task | 7.5MB | 手部关键点 |

**来源**: https://www.modelscope.cn/models/archifancy/AIGlasses_for_navigation

---

## 四、依赖安装

```bash
pip install fastapi uvicorn websockets python-multipart starlette python-dotenv
pip install dashscope openai pydub pygame pyaudio
pip install ultralytics mediapipe open-clip-torch
pip install torch==2.0.1+cu118 torchvision  # GPU 版
```

---

## 五、当前 AI 功能状态

| 功能 | 状态 | 语音指令 |
|---|---|---|
| 盲道导航 | ✅ | "开始导航" |
| 过马路辅助 | ✅ | "开始过马路" |
| 红绿灯检测 | ✅ | "检测红绿灯" |
| 物品搜索 | ✅ | "帮我找一下矿泉水" |
| 语音识别(ASR) | ✅ | 自动激活 |
| AI 语音对话 | ✅ | 任意问题 |
| 障碍物检测 | ⚠️ | 需下载 CLIP 权重 (572MB) |

---

## 六、服务器运行

```bash
cd OpenAIglasses_for_Navigation
pip install -r requirements.txt   # 首次
编辑 .env 填入 DASHSCOPE_API_KEY
python app_main.py                # 启动
# 访问 http://localhost:8081
```

## 七、手机使用

1. 安装 `AIGlass-demo.apk`
2. 确保手机与电脑同一 WiFi
3. App 设置中填入电脑 IP 和端口 8081
4. 对手机说话触发 AI 功能

---

## 八、已知问题

1. **Python 3.13**: 部分包兼容性差（mediapipe 功能受限）
2. **CLIP 权重**: 需通畅网络下载 572MB（障碍物检测用）
3. **模拟器**: 虚拟麦克风不产生有效语音，ASR 无法识别
4. **CUDA**: 需 NVIDIA GPU + CUDA 11.8，CPU 推理较慢

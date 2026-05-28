# Visus 智能导航助手

面向视障人士的 AI 辅助出行系统，集成盲道导航、过马路辅助、物品识别、实时语音交互等功能。

**⚠️ 免责声明**：本项目仅供学习交流使用，请勿直接用于视障人群的实际出行场景。

---

## 📁 项目结构

本项目分为两大模块：

```
Visus-main/
├── app/                          # 应用端（Android 客户端）
│   └── android/                  # Android 原生应用源码
│       ├── app/src/main/         # Kotlin 源码、资源文件
│       ├── build.gradle.kts      # Gradle 构建配置
│       └── Visus-demo.apk        # 预编译 APK（可直接安装）
│
├── server/                       # 服务器端（Python 后端 + Web 监控）
│   ├── src/                      # Python 核心源码
│   │   ├── core/                 # FastAPI 主服务、状态管理
│   │   ├── navigation/           # 导航工作流（盲道、过马路）
│   │   ├── vision/               # 视觉检测（YOLO、MediaPipe）
│   │   ├── voice/                # 语音处理（ASR、TTS、Omni）
│   │   ├── audio/                # 音频工具（压缩、录制）
│   │   └── utils/                # 工具模块
│   ├── web/                      # Web 前端（监控页面）
│   │   ├── templates/            # HTML 模板
│   │   └── static/               # JS / CSS / 资源
│   ├── config/                   # 部署配置
│   │   ├── requirements.txt      # Python 依赖
│   │   ├── Dockerfile            # Docker 镜像构建
│   │   └── docker-compose.yml    # Docker Compose 部署
│   ├── assets/                   # 资源文件（模型、音频提示）
│   └── docs/                     # 文档与许可证
│
└── README.md                     # 本文件
```

---

## ✨ 功能特性

### 🚶 盲道导航系统
- **实时盲道检测**：基于 YOLO 分割模型实时识别盲道位置
- **智能语音引导**：提供精准的方向指引（左转、右转、直行、停步等）
- **障碍物检测与避障**：自动识别前方障碍物并语音提醒
- **转弯检测**：自动识别急转弯并提前播报
- **光流稳定**：使用 Lucas-Kanade 光流算法稳定掩码，减少画面抖动

### 🚦 过马路辅助
- **斑马线识别**：实时检测斑马线位置和方向
- **红绿灯识别**：基于颜色和形状的红绿灯状态检测
- **对齐引导**：语音引导用户对准斑马线中心
- **安全提醒**：绿灯时语音提示可以通行，红灯时提醒等待

### 🔍 物品识别与查找
- **智能物品搜索**：语音指令查找物品（如"帮我找一下矿泉水"）
- **实时目标追踪**：使用 YOLO-E 开放词汇检测 + ByteTrack 追踪
- **手部引导**：结合 MediaPipe 手部检测，引导用户手部靠近物品
- **抓取检测**：检测手部握持动作，确认物品已拿到
- **多模态反馈**：视觉标注 + 语音引导 + 居中提示

### 🎙️ 实时语音交互
- **语音识别（ASR）**：当前基于 DashScope Paraformer 实时语音识别
- **多模态对话**：基于火山引擎方舟豆包模型，支持文本和图像输入
- **智能指令解析**：自动识别导航、查找、对话等不同类型指令
- **上下文感知**：在不同模式下智能过滤无关指令

### 📹 视频与音频处理
- **实时视频流**：WebSocket 推流，支持多客户端同时观看
- **音视频同步录制**：自动保存带时间戳的录像和音频文件
- **多路音频混音**：支持系统语音、AI 回复、环境音同时播放
- **音频压缩**：支持 ADPCM、μ-law 等压缩算法，降低传输带宽

### 🎨 可视化与交互
- **Web 实时监控**：浏览器端实时查看处理后的视频流
- **状态面板**：显示导航状态、检测信息、FPS 等
- **中文友好**：所有界面和语音使用中文，支持自定义字体

---

## 💻 系统要求

### 硬件要求
- **服务器端（电脑）**：
  - CPU: Intel i5 或以上（推荐 i7/i9）
  - GPU: NVIDIA GPU（支持 CUDA 11.8+，推荐 RTX 3060 或以上）
  - 内存: 8GB RAM（推荐 16GB）
  - 存储: 10GB 可用空间
  - 网络: 与手机处于同一局域网

- **客户端（安卓手机）**：
  - Android 8.0 (API 26) 或更高版本
  - 摄像头（用于拍摄前方画面）
  - 麦克风（用于语音输入）
  - 扬声器/耳机（用于语音输出）

### 软件要求
- **操作系统**: Windows 10/11, Linux (Ubuntu 20.04+), macOS 10.15+
- **Python**: 3.9 - 3.11（推荐 3.10/3.11）
- **CUDA**: 11.8 或更高版本（GPU 加速必需）
- **浏览器**: Chrome 90+, Firefox 88+, Edge 90+（用于 Web 监控）

### API 密钥
- **火山引擎方舟 API Key**（必需）：
  - 用于豆包大模型对话、图像理解和物品名称归一化
  - 需要配置方舟推理接入点 ID（`ARK_MODEL` / `DOUBAO_MODEL`）
- **火山引擎语音合成配置**（必需）：
  - 用于把豆包文本回复合成为手机端可播放的语音
  - 需要配置 `VOLCENGINE_TTS_APP_ID`、`VOLCENGINE_TTS_ACCESS_TOKEN`、`VOLCENGINE_TTS_CLUSTER`、`VOLCENGINE_TTS_VOICE_TYPE`
- **阿里云 DashScope API Key**（当前仍必需）：
  - 仅用于实时语音识别（ASR）
  - 如果后续接入火山实时 ASR，可以移除此项

---

## 🚀 快速开始

### 一、服务器端部署

#### 1. 进入服务器目录

```bash
cd server
```

#### 2. 安装依赖

```bash
# 创建虚拟环境（推荐）
python -m venv venv

# Windows
venv\Scripts\activate

# Linux/macOS
source venv/bin/activate

# 安装 Python 包
pip install -r config/requirements.txt
```

#### 3. 安装 PyTorch（GPU 版本）

```bash
pip install torch==2.0.1+cu118 torchvision==0.15.2+cu118 --index-url https://download.pytorch.org/whl/cu118
```

如果使用 CPU 模式：
```bash
pip install torch torchvision
```

> **注意**：Windows 上 PyAudio 可能需要手动安装，请访问 https://www.lfd.uci.edu/~gohlke/pythonlibs/#pyaudio

#### 4. 下载模型文件

将以下模型文件放入 `server/assets/models/` 目录：

| 模型文件 | 用途 | 大小 | 来源 |
|---------|------|------|------|
| `yolo-seg.pt` | 盲道分割 | ~50MB | [ModelScope](https://www.modelscope.cn/models/archifancy/Visus_for_navigation) |
| `yoloe-11l-seg.pt` | 开放词汇检测 | ~80MB | [ModelScope](https://www.modelscope.cn/models/archifancy/Visus_for_navigation) |
| `shoppingbest5.pt` | 物品识别 | ~30MB | [ModelScope](https://www.modelscope.cn/models/archifancy/Visus_for_navigation) |
| `trafficlight.pt` | 红绿灯检测 | ~20MB | [ModelScope](https://www.modelscope.cn/models/archifancy/Visus_for_navigation) |
| `hand_landmarker.task` | 手部检测 | ~15MB | [MediaPipe Models](https://developers.google.com/mediapipe/solutions/vision/hand_landmarker#models) |

> 模型下载地址：https://www.modelscope.cn/models/archifancy/Visus_for_navigation

#### 5. 配置 API 密钥

在 `server/` 目录创建 `.env` 文件：

```bash
# .env
ARK_API_KEY=your-volcengine-ark-api-key
ARK_MODEL=your-ark-endpoint-id
ARK_BASE_URL=https://ark.cn-beijing.volces.com/api/v3

VOLCENGINE_TTS_APP_ID=your-volcengine-tts-app-id
VOLCENGINE_TTS_ACCESS_TOKEN=your-volcengine-tts-access-token
VOLCENGINE_TTS_CLUSTER=volcano_tts
VOLCENGINE_TTS_VOICE_TYPE=BV700_V2_streaming
VOLCENGINE_TTS_ENCODING=wav

# 当前实时 ASR 仍使用 DashScope
DASHSCOPE_API_KEY=sk-your-dashscope-key-here
```

或在启动前设置环境变量：

```bash
# Windows PowerShell
$env:ARK_API_KEY="your-volcengine-ark-api-key"
$env:ARK_MODEL="your-ark-endpoint-id"
$env:ARK_BASE_URL="https://ark.cn-beijing.volces.com/api/v3"
$env:VOLCENGINE_TTS_APP_ID="your-volcengine-tts-app-id"
$env:VOLCENGINE_TTS_ACCESS_TOKEN="your-volcengine-tts-access-token"
$env:VOLCENGINE_TTS_CLUSTER="volcano_tts"
$env:VOLCENGINE_TTS_VOICE_TYPE="BV700_V2_streaming"
$env:DASHSCOPE_API_KEY="sk-your-dashscope-key-here"

# Linux/macOS
export ARK_API_KEY="your-volcengine-ark-api-key"
export ARK_MODEL="your-ark-endpoint-id"
export ARK_BASE_URL="https://ark.cn-beijing.volces.com/api/v3"
export VOLCENGINE_TTS_APP_ID="your-volcengine-tts-app-id"
export VOLCENGINE_TTS_ACCESS_TOKEN="your-volcengine-tts-access-token"
export VOLCENGINE_TTS_CLUSTER="volcano_tts"
export VOLCENGINE_TTS_VOICE_TYPE="BV700_V2_streaming"
export DASHSCOPE_API_KEY="sk-your-dashscope-key-here"
```

#### 6. 启动服务器

```bash
python src/core/app_main.py
```

服务器将在 `http://0.0.0.0:8081` 启动，打开浏览器访问即可看到实时监控界面。

---

### 二、客户端安装（Android）

#### 方式一：直接安装 APK

1. 将 `app/android/Visus-demo.apk` 传输到 Android 手机
2. 允许安装未知来源应用
3. 安装并打开 App

#### 方式二：自行编译

1. 使用 Android Studio 打开 `app/android/` 目录
2. 同步 Gradle，编译项目
3. 生成 APK 或直接在模拟器/真机上运行

#### 配置服务器地址

1. 打开 App → 点击右上角齿轮图标进入设置
2. 输入电脑的局域网 IP 地址（如 `192.168.1.100`）
3. 端口填写 `8081`
4. 保存后自动连接

---

## 🏗️ 系统架构

### 整体架构

```
┌─────────────────────────────────────────────────────────────┐
│                        客户端层                              │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐      │
│  │  Android App │  │   浏览器      │  │   移动端      │      │
│  │  (视频/音频)  │  │  (监控界面)   │  │  (语音控制)   │      │
│  └──────┬───────┘  └──────┬───────┘  └──────┬───────┘      │
└─────────┼──────────────────┼──────────────────┼─────────────┘
          │ WebSocket        │ HTTP/WS          │ WebSocket
┌─────────┼──────────────────┼──────────────────┼─────────────┐
│         │                  │                  │              │
│    ┌────▼──────────────────▼──────────────────▼────────┐    │
│    │         FastAPI 主服务 (app_main.py)              │    │
│    │  - WebSocket 路由管理                              │    │
│    │  - 音视频流分发                                     │    │
│    │  - 状态管理与协调                                   │    │
│    └────┬────────────────┬────────────────┬─────────────┘    │
│         │                │                │                  │
│  ┌──────▼──────┐  ┌──────▼──────┐  ┌──────▼──────┐         │
│  │ ASR 模块     │  │ Omni 对话   │  │ 音频播放     │         │
│  │ (asr_core)   │  │(omni_client)│  │(audio_player)│         │
│  └──────────────┘  └──────────────┘  └──────────────┘         │
│                                                               │
│         应用层                                                │
└───────────────────────────────────────────────────────────────┘
          │                  │                  │
┌─────────▼──────────────────▼──────────────────▼──────────────┐
│                     导航统领层                                │
│    ┌─────────────────────────────────────────────────┐       │
│    │  NavigationMaster (navigation_master.py)         │       │
│    │  - 状态机：IDLE/CHAT/BLINDPATH_NAV/              │       │
│    │            CROSSING/TRAFFIC_LIGHT/ITEM_SEARCH    │       │
│    │  - 模式切换与协调                                │       │
│    └───┬─────────────────────┬───────────────────┬───┘       │
│        │                     │                   │            │
│   ┌────▼────────┐   ┌────────▼────────┐   ┌─────▼──────┐   │
│   │ 盲道导航     │   │  过马路导航      │   │ 物品查找    │   │
│   │(blindpath)   │   │ (crossstreet)   │   │(yolomedia)  │   │
│   └──────────────┘   └──────────────────┘   └─────────────┘   │
└───────────────────────────────────────────────────────────────┘
          │                  │                  │
┌─────────▼──────────────────▼──────────────────▼──────────────┐
│                       模型推理层                              │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐       │
│  │ YOLO 分割     │  │  YOLO-E 检测 │  │ MediaPipe    │       │
│  │ (盲道/斑马线) │  │ (开放词汇)   │  │  (手部检测)   │       │
│  └──────────────┘  └──────────────┘  └──────────────┘       │
│  ┌──────────────┐  ┌──────────────┐                         │
│  │ 红绿灯检测    │  │ 光流稳定      │                         │
│  │(HSV+YOLO)     │  │(Lucas-Kanade)│                         │
│  └──────────────┘  └──────────────┘                         │
└───────────────────────────────────────────────────────────────┘
          │
┌─────────▼─────────────────────────────────────────────────────┐
│                    外部服务层                                  │
│  ┌──────────────────────────────────────────────┐            │
│  │  阿里云 DashScope API                         │            │
│  │  - Paraformer ASR (实时语音识别)              │            │
│  │  - Qwen-Omni-Turbo (多模态对话)               │            │
│  │  - Qwen-Turbo (标签提取)                      │            │
│  └──────────────────────────────────────────────┘            │
└───────────────────────────────────────────────────────────────┘
```

### 核心模块说明

| 模块 | 文件 | 功能 |
|------|------|------|
| **主应用** | `server/src/core/app_main.py` | FastAPI 服务、WebSocket 管理、状态协调 |
| **导航统领** | `server/src/core/navigation_master.py` | 状态机管理、模式切换、语音节流 |
| **模型管理** | `server/src/core/models.py` | AI 模型加载与设备管理 |
| **盲道导航** | `server/src/navigation/workflow_blindpath.py` | 盲道检测、避障、转弯引导 |
| **过马路导航** | `server/src/navigation/workflow_crossstreet.py` | 斑马线检测、红绿灯识别、对齐引导 |
| **斑马线感知** | `server/src/navigation/crosswalk_awareness.py` | 斑马线位置与方向检测 |
| **红绿灯检测** | `server/src/navigation/trafficlight_detection.py` | 红绿灯颜色与状态识别 |
| **物品查找** | `server/src/vision/yolomedia.py` | 物品检测、手部引导、抓取确认 |
| **YOLO-E 后端** | `server/src/vision/yoloe_backend.py` | 开放词汇检测后端 |
| **障碍物检测** | `server/src/vision/obstacle_detector_client.py` | 障碍物检测客户端 |
| **语音识别** | `server/src/voice/asr_core.py` | 实时 ASR、VAD、指令解析 |
| **语音合成** | `server/src/voice/omni_client.py` | Qwen-Omni 流式语音生成 |
| **音频播放** | `server/src/voice/audio_player.py` | 多路混音、TTS 播放、音量控制 |
| **音频流** | `server/src/voice/audio_stream.py` | 音频流管理与 WebSocket 推流 |
| **音频压缩** | `server/src/audio/audio_compressor.py` | ADPCM/μ-law 音频压缩 |
| **同步录制** | `server/src/audio/sync_recorder.py` | 音视频同步录制 |
| **桥接 IO** | `server/src/utils/bridge_io.py` | 线程安全的帧缓冲与分发 |

---

## 📖 使用说明

### 语音指令

系统支持以下语音指令（说话时无需唤醒词）：

#### 导航控制
| 指令 | 功能 |
|------|------|
| "开始导航" / "盲道导航" | 启动盲道导航 |
| "停止导航" / "结束导航" | 停止盲道导航 |
| "开始过马路" / "帮我过马路" | 启动过马路模式 |
| "过马路结束" / "结束过马路" | 停止过马路模式 |

#### 红绿灯检测
| 指令 | 功能 |
|------|------|
| "检测红绿灯" / "看红绿灯" | 启动红绿灯检测 |
| "停止检测" / "停止红绿灯" | 停止检测 |

#### 物品查找
| 指令 | 功能 |
|------|------|
| "帮我找一下 [物品名]" | 启动物品搜索 |
| "找到了" / "拿到了" | 确认找到物品 |

**示例**：
- "帮我找一下红牛"
- "找一下 AD 钙奶"
- "帮我找矿泉水"

#### 智能对话
| 指令 | 功能 |
|------|------|
| "帮我看看这是什么" | 拍照识别 |
| "这个东西能吃吗" | 物品咨询 |
| 任意其他问题 | AI 对话 |

### 导航状态说明

系统包含以下主要状态（自动切换）：

1. **IDLE** - 空闲状态
   - 等待用户指令
   - 显示原始视频流

2. **CHAT** - 对话模式
   - 与 AI 进行多模态对话
   - 暂停导航功能

3. **BLINDPATH_NAV** - 盲道导航
   - **ONBOARDING**: 上盲道引导（旋转对准 → 平移至中心）
   - **NAVIGATING**: 沿盲道行走（实时方向修正、障碍物检测）
   - **MANEUVERING_TURN**: 转弯处理
   - **AVOIDING_OBSTACLE**: 避障

4. **CROSSING** - 过马路模式
   - **SEEKING_CROSSWALK**: 寻找斑马线
   - **WAIT_TRAFFIC_LIGHT**: 等待绿灯
   - **CROSSING**: 过马路中
   - **SEEKING_NEXT_BLINDPATH**: 寻找对面盲道

5. **ITEM_SEARCH** - 物品查找
   - 实时检测目标物品
   - 引导手部靠近
   - 确认抓取

6. **TRAFFIC_LIGHT_DETECTION** - 红绿灯检测
   - 实时检测红绿灯状态
   - 语音播报颜色变化

### Web 监控界面

打开浏览器访问 `http://localhost:8081`，可以看到：

- **实时视频流**：显示处理后的视频，包括导航标注
- **状态面板**：当前模式、检测信息、FPS
- **语音识别结果**：显示识别的文字和 AI 回复
- **聊天界面**：左右气泡式对话展示

### WebSocket 端点

| 端点 | 用途 | 数据格式 |
|------|------|---------|
| `/ws/camera` | 移动端相机推流 | Binary (JPEG) |
| `/ws/viewer` | 浏览器订阅视频 | Binary (JPEG) |
| `/ws_audio` | 移动端音频上传 | Binary (PCM16) |
| `/ws_ui` | UI 状态推送 | JSON |
| `/stream.wav` | 音频下载流 | Binary (WAV) |

---

## ⚙️ 配置说明

### 环境变量

在 `server/` 目录创建 `.env` 文件配置以下参数：

```bash
# 阿里云 API（必需）
DASHSCOPE_API_KEY=sk-xxxxx

# 模型路径（可选，使用默认路径可不配置）
BLIND_PATH_MODEL=assets/models/yolo-seg.pt
OBSTACLE_MODEL=assets/models/yoloe-11l-seg.pt
YOLOE_MODEL_PATH=assets/models/yoloe-11l-seg.pt

# 设备配置
VISUS_DEVICE=cuda:0           # 计算设备（cuda:0 或 cpu）
VISUS_AMP=bf16                # 自动混合精度（bf16/fp16/fp32）
VISUS_GPU_SLOTS=2             # GPU 并发槽位数

# 导航参数
VISUS_MASK_MIN_AREA=1500      # 最小掩码面积
VISUS_MASK_MORPH=3            # 形态学核大小
VISUS_MASK_MISS_TTL=6         # 掩码丢失容忍帧数
VISUS_PANEL_SCALE=0.65        # 数据面板缩放
VISUS_STRAIGHT_INTERVAL=4.0   # 直行播报间隔（秒）
VISUS_DIRECTION_INTERVAL=3.0  # 方向指令间隔（秒）
VISUS_OBS_INTERVAL=15         # 障碍物检测间隔（帧）
VISUS_BLINDPATH_INTERVAL=8    # 盲道检测间隔（帧）
VISUS_CROSSWALK_INTERVAL=4    # 斑马线检测间隔（帧）

# 音频配置
VISUS_COMPRESS_AUDIO=1        # 启用音频压缩（1=启用，0=禁用）
VISUS_COMPRESS_TYPE=adpcm     # 压缩类型（adpcm/ulaw/none）
TTS_INTERVAL_SEC=1.0          # 语音播报间隔
ENABLE_TTS=true               # 启用语音播报
```

### 修改模型路径

如果模型文件不在默认位置，可以在相应文件中修改：

```python
# server/src/navigation/workflow_blindpath.py
seg_model_path = "your/custom/path/yolo-seg.pt"

# server/src/vision/yolomedia.py
YOLO_MODEL_PATH = "your/custom/path/shoppingbest5.pt"
HAND_TASK_PATH = "your/custom/path/hand_landmarker.task"
```

### 调整性能参数

根据硬件性能调整：

```python
# server/src/vision/yolomedia.py
HAND_DOWNSCALE = 0.8    # 手部检测降采样（越小越快，精度降低）
HAND_FPS_DIV = 1        # 手部检测抽帧（2=隔帧，3=每3帧）

# server/src/navigation/workflow_blindpath.py  
FEATURE_PARAMS = dict(
    maxCorners=600,      # 光流特征点数（越少越快）
    qualityLevel=0.001,  # 特征点质量
    minDistance=5        # 特征点最小间距
)
```

---

## 🛠️ 开发文档

### 添加新的语音指令

1. 在 `server/src/core/app_main.py` 的 `start_ai_with_text_custom()` 函数中添加：

```python
# 检查新指令
if "新指令关键词" in user_text:
    # 执行自定义逻辑
    print("[CUSTOM] 新指令被触发")
    await ui_broadcast_final("[系统] 新功能已启动")
    return
```

2. 如需修改指令过滤规则：

```python
# 修改 allowed_keywords 列表
allowed_keywords = ["帮我看", "帮我找", "你的新关键词"]
```

### 扩展导航功能

1. 在 `server/src/navigation/workflow_blindpath.py` 添加新状态：

```python
# 在 BlindPathNavigator.__init__() 中初始化
self.your_new_state_var = False

# 在 process_frame() 中处理
def process_frame(self, image):
    if self.your_new_state_var:
        # 自定义处理逻辑
        guidance_text = "新状态引导"
```

2. 在 `server/src/core/navigation_master.py` 添加状态机状态：

```python
class NavigationMaster:
    def start_your_new_mode(self):
        self.state = "YOUR_NEW_MODE"
        # 初始化逻辑
```

### 集成新模型

1. 创建模型包装类：

```python
# your_model_wrapper.py
class YourModelWrapper:
    def __init__(self, model_path):
        self.model = load_your_model(model_path)
    
    def detect(self, image):
        # 推理逻辑
        return results
```

2. 在 `server/src/core/app_main.py` 中加载：

```python
your_model = YourModelWrapper("assets/models/your_model.pt")
```

3. 在相应的工作流中调用：

```python
results = your_model.detect(image)
```

### 调试技巧

1. **启用详细日志**：

```python
# app_main.py 顶部
import logging
logging.basicConfig(level=logging.DEBUG)
```

2. **查看帧率瓶颈**：

```python
# yolomedia.py
PERF_DEBUG = True  # 打印处理时间
```

---

### 自定义系统提示词

AI 对话的默认系统提示词位于 `server/prompt.txt`，定义了面向视障人士的辅助服务行为准则，包括：

- **路况识别**：描述前方道路环境，区分人行道、马路、台阶、障碍物等
- **物品找寻**：精准描述物品位置、外观、距离，指引伸手方向
- **电梯/门体判断**：识别电梯运行状态、门开关状态
- **导航指引**：给出直行、左转、右转等行动指令
- **通用视觉解读**：识别文字、标识、红绿灯、车牌等
- **情感陪伴**：温和耐心的语气，危险场景下的情绪安抚

如需修改 AI 的说话风格或增加新的行为规则，直接编辑 `server/prompt.txt` 即可，无需重启服务（下次对话自动生效）。

---

## 🐳 Docker 部署

### 使用 Docker Compose

```bash
cd server

docker-compose -f config/docker-compose.yml up -d
```

### 构建镜像

```bash
cd server

docker build -f config/Dockerfile -t visus:latest .
docker run -p 8081:8081 --gpus all visus:latest
```

---

## 📝 更新日志

### 最新版本 [1.0.0]

- 盲道导航系统（实时检测、语音引导、避障）
- 过马路辅助（斑马线识别、红绿灯检测）
- 物品识别与查找（YOLO-E + 手部引导）
- 实时语音交互（ASR + Qwen-Omni）
- Web 实时监控界面
- 安卓端 App

---

## 🤝 贡献指南

欢迎提交 Issue 和 Pull Request！

1. Fork 本仓库
2. 创建特性分支 (`git checkout -b feature/AmazingFeature`)
3. 提交更改 (`git commit -m 'Add some AmazingFeature'`)
4. 推送分支 (`git push origin feature/AmazingFeature`)
5. 创建 Pull Request

---

## 📄 许可证

本项目采用 MIT 许可证 - 详见 [server/docs/LICENSE](server/docs/LICENSE) 文件

---

## 🙏 致谢

- [Ultralytics YOLO](https://github.com/ultralytics/ultralytics) - 目标检测与分割
- [MediaPipe](https://developers.google.com/mediapipe) - 手部检测
- [阿里云 DashScope](https://dashscope.console.aliyun.com/) - ASR 与多模态对话
- [FastAPI](https://fastapi.tiangolo.com/) - Web 框架
- [OpenCV](https://opencv.org/) - 计算机视觉

---

<div align="center">

**[⬆ 回到顶部](#visus-智能导航助手)**

Made with ❤️ for a more accessible world

</div>

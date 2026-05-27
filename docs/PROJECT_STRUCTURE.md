# 项目结构说明

本文档详细说明重组后的项目目录结构和主要文件的作用。

## 📁 目录结构

```
Visus-main/
├── 📁 src/                          # 核心源代码
│   ├── 📁 core/                     # 主应用与状态管理
│   │   ├── app_main.py              # FastAPI主服务入口
│   │   ├── navigation_master.py     # 导航状态机（统领器）
│   │   └── models.py                # 模型定义与管理
│   │
│   ├── 📁 navigation/               # 导航工作流
│   │   ├── workflow_blindpath.py    # 盲道导航核心逻辑
│   │   ├── workflow_crossstreet.py  # 过马路辅助逻辑
│   │   ├── crosswalk_awareness.py   # 斑马线感知模块
│   │   └── trafficlight_detection.py # 红绿灯检测模块
│   │
│   ├── 📁 vision/                   # 视觉检测
│   │   ├── yolomedia.py             # 物品查找工作流（YOLO-E + 手部追踪）
│   │   ├── yoloe_backend.py         # YOLO-E后端（开放词汇检测）
│   │   ├── obstacle_detector_client.py # 障碍物检测客户端
│   │   └── mobileclip_blt.ts        # MobileCLIP类型定义
│   │
│   ├── 📁 voice/                    # 语音处理
│   │   ├── asr_core.py              # 阿里云Paraformer ASR语音识别
│   │   ├── omni_client.py           # Qwen-Omni多模态对话客户端
│   │   ├── audio_stream.py          # 音频流管理（WebSocket推流）
│   │   ├── audio_player.py          # 音频播放器（预录语音）
│   │   ├── qwen_extractor.py        # 中文→英文标签提取
│   │   └── qwenturbo_template.py    # 对话模板
│   │
│   ├── 📁 audio/                    # 音频工具
│   │   ├── audio_compressor.py      # 音频压缩工具
│   │   ├── audioop_shim.py          # audioop兼容层（Python 3.13+）
│   │   └── sync_recorder.py         # 音视频同步录制器
│   │
│   └── 📁 utils/                    # 工具模块
│       ├── utils.py                 # 通用工具函数（名称映射、光流等）
│       └── bridge_io.py             # 线程安全的帧缓冲桥
│
├── 📁 web/                          # Web前端
│   ├── 📁 templates/
│   │   └── index.html               # 主界面HTML
│   └── 📁 static/
│       ├── main.js                  # 主JS脚本（摄像头+ASR）
│       ├── vision.js                # 视觉流处理
│       ├── vision_renderer.js       # 渲染器
│       ├── visualizer.js            # IMU数据可视化（Three.js）
│       ├── vision.css               # 样式表
│       └── 📁 models/
│           └── aiglass.glb          # 3D眼镜模型（IMU可视化）
│
├── 📁 mobile/                       # 移动端应用
│   ├── 📁 gradle/wrapper/           # Gradle包装器
│   ├── AIGlass-demo.apk             # 预编译APK
│   ├── build.gradle.kts             # Gradle构建配置
│   ├── gradle.properties            # Gradle属性
│   ├── gradlew.bat                  # Gradle启动脚本
│   └── settings.gradle.kts          # Gradle设置
│
├── 📁 firmware/                     # 嵌入式固件
│   └── 📁 esp32/
│       ├── compile.ino              # Arduino主程序（摄像头+麦克风+IMU）
│       ├── camera_pins.h            # 摄像头引脚定义
│       ├── ICM42688.cpp             # IMU驱动（SPI）
│       └── ICM42688.h               # IMU驱动头文件
│
├── 📁 assets/                       # 资源文件
│   ├── 📁 audio_prompts/            # 音频提示资源
│   │   ├── map.zh-CN.json           # 语音映射表
│   │   ├── 📁 navigation/           # 导航指令语音（方向提示等）
│   │   └── 📁 system/               # 系统提示语音（状态播报等）
│   └── 📁 models/                   # AI模型文件
│       └── hand_landmarker.task     # MediaPipe手部检测模型
│
├── 📁 config/                       # 配置文件
│   ├── requirements.txt             # Python依赖
│   ├── Dockerfile                   # Docker镜像定义
│   ├── docker-compose.yml           # Docker Compose配置
│   ├── setup.sh                     # Linux/macOS安装脚本
│   └── setup.bat                    # Windows安装脚本
│
├── 📁 docs/                         # 文档
│   ├── README.md                    # 项目主文档
│   ├── PROJECT_STRUCTURE.md         # 本文件（项目结构说明）
│   ├── CHANGELOG.md                 # 更新日志
│   ├── ANDROID_STUDIO_BUILD.md      # Android Studio构建指南
│   ├── LICENSE                      # MIT许可证
│   ├── 使用说明.md                   # 中文使用说明
│   └── screenshot.png               # 项目截图
│
└── .gitignore                       # Git忽略文件
```

## 🔑 核心模块说明

### src/core/ - 核心应用层

#### app_main.py
- **作用**: FastAPI主服务，处理所有WebSocket连接
- **主要功能**:
  - WebSocket路由管理（/ws/camera, /ws_audio, /ws/viewer等）
  - 模型加载与初始化
  - 状态协调与管理
  - 音视频流分发
- **入口点**: `python src/core/app_main.py`

#### navigation_master.py
- **作用**: 导航统领器，管理整个系统的状态机
- **主要状态**: IDLE, CHAT, BLINDPATH_NAV, CROSSING, TRAFFIC_LIGHT_DETECTION, ITEM_SEARCH

### src/navigation/ - 导航工作流

#### workflow_blindpath.py
- **作用**: 盲道导航核心逻辑
- **功能**: 盲道分割、障碍物检测、转弯检测、光流稳定、方向引导

#### workflow_crossstreet.py
- **作用**: 过马路导航逻辑
- **功能**: 斑马线检测、方向对齐、红绿灯等待

### src/vision/ - 视觉检测

#### yolomedia.py
- **作用**: 物品查找工作流
- **功能**: YOLO-E文本提示检测、MediaPipe手部追踪、抓取检测

### src/voice/ - 语音处理

#### asr_core.py
- **作用**: 阿里云Paraformer实时语音识别
- **功能**: 实时语音识别、VAD语音活动检测

#### omni_client.py
- **作用**: Qwen-Omni-Turbo多模态对话客户端
- **功能**: 流式对话、图像+文本输入、语音输出

## 🚀 快速启动

```bash
# 1. 安装依赖
pip install -r config/requirements.txt

# 2. 设置环境变量
export DASHSCOPE_API_KEY="your-api-key"

# 3. 启动服务
python src/core/app_main.py
```

## 📝 注意事项

- 所有Python导入路径已更新为相对包路径
- 模型文件应放在 `assets/models/` 目录
- 音频资源文件应放在 `assets/audio_prompts/` 目录
- Web静态文件位于 `web/static/` 目录

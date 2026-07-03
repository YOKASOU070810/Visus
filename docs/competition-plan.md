# Visus 作品策划文档

## 1. 作品概述

Visus 是一款面向视力障碍用户的 AI 辅助出行应用。作品由 Android 客户端和 FastAPI 后端组成，围绕“看路、问路、求助、联系家人”四类高频场景设计，使用语音、摄像头、地图服务和大模型能力，为用户提供更自然的出行辅助体验。

作品的核心目标不是替代导盲犬、盲杖或专业无障碍设备，而是在常见校园、社区、室内外通行场景中，为用户提供低成本、可部署、可演示的智能辅助原型。

## 2. 目标用户与痛点

目标用户：

- 视力障碍用户
- 短期视力受限或夜间低视力人群
- 需要远程关注视障用户安全状态的家属、朋友、志愿者

主要痛点：

- 出行过程中难以及时判断前方环境和障碍物。
- 问路、找地点、切换导航 App 的操作成本较高。
- 遇到紧急情况时，难以快速通知好友并发送状态。
- 普通地图导航缺少适合语音播报的简洁交互。

## 3. 核心功能设计

### 3.1 主界面 AI 助手

用户可通过语音或文字输入自然语言指令。AI Agent 会理解用户意图，并映射到具体 App 功能。

示例：

- “你好” -> 普通对话回复
- “附近医院” -> 搜索附近医院
- “开启辅助出行” -> 切换到辅助出行页并启动摄像头推流
- “去人民广场” -> 切换到地图导航页并自动规划路线
- “紧急求助” -> 触发 SOS 通知

### 3.2 辅助出行

手机端采集摄像头画面和麦克风音频，通过 WebSocket 推送给后端。后端处理画面、语音识别和 AI 回复，再把文字、音频和预览画面回传给 App。

主要能力：

- 手机摄像头推流
- 实时语音识别
- AI 语音播报
- 障碍物/场景相关辅助提示
- 推流状态显示

### 3.3 地图导航

用户可以手动输入目的地，也可以通过主界面 AI 触发导航。后端调用地图服务完成地理编码、周边搜索和步行路线规划，并生成适合语音播报的摘要。

### 3.4 好友与紧急求助

用户可添加好友、查看好友状态。紧急情况下，用户可以触发 SOS，后端会通过 WebSocket 和通知服务把求助信息推送给好友端。

## 4. 系统架构

```text
Android App
  ├─ Jetpack Compose UI
  ├─ Text / Speech input
  ├─ Camera + Microphone streaming
  ├─ TTS playback
  └─ HTTP / WebSocket client

FastAPI Backend
  ├─ Auth / Friends / SOS APIs
  ├─ AI Agent endpoint
  ├─ Map navigation endpoint
  ├─ Camera / audio WebSocket endpoints
  ├─ ASR / LLM / TTS adapters
  └─ SQLite database

External APIs
  ├─ Volcengine Ark LLM
  ├─ Volcengine TTS
  ├─ DashScope realtime ASR
  └─ AMap map APIs
```

## 5. 大模型矩阵 API 实际调用

### 5.1 火山方舟大模型

用途：

- 主界面 AI Agent 意图理解
- 辅助出行对话回复
- 结合用户问题和当前画面生成简短语音回复

配置项：

```env
ARK_API_KEY=your-volcengine-ark-api-key
ARK_MODEL=your-ark-endpoint-id
ARK_BASE_URL=https://ark.cn-beijing.volces.com/api/v3
```

主界面 AI 请求入口：

```text
POST /api/agent/command
```

请求示例：

```json
{
  "text": "去附近医院",
  "user_id": 1,
  "lat": 31.2304,
  "lng": 121.4737,
  "city": "上海"
}
```

返回示例：

```json
{
  "success": true,
  "data": {
    "action": "search_nearby",
    "params": {
      "keywords": "医院"
    },
    "reply_text": "正在为你搜索附近的医院。",
    "extra": {}
  }
}
```

### 5.2 DashScope 实时语音识别

用途：

- 辅助出行页的实时语音输入
- 把用户语音转为文字，再交给后端 AI 回复

配置项：

```env
DASHSCOPE_API_KEY=your-dashscope-asr-key
```

### 5.3 火山语音合成

用途：

- 将 AI 文本回复合成为语音
- 让手机端播放更自然的语音播报

配置项：

```env
VOLCENGINE_TTS_APP_ID=your-volcengine-tts-app-id
VOLCENGINE_TTS_ACCESS_TOKEN=your-volcengine-tts-access-token
VOLCENGINE_TTS_CLUSTER=volcano_tts
VOLCENGINE_TTS_VOICE_TYPE=BV700_V2_streaming
VOLCENGINE_TTS_ENCODING=wav
```

### 5.4 高德地图 API

用途：

- 地理编码
- 逆地理编码
- 周边搜索
- 步行路径规划

App 可以在“我的/设置”中填写高德 Key；后端也支持从请求头读取。

## 6. 可运行版本

仓库中提供可安装 Debug APK：

```text
release/Visus-v1.0.0-debug.apk
```

后端启动脚本：

```powershell
powershell -ExecutionPolicy Bypass -File server\start_backend.ps1
```

源码压缩包建议通过脚本生成，避免包含 `.env`、`.venv`、`.git`、`build` 等不应提交内容：

```powershell
powershell -ExecutionPolicy Bypass -File scripts\package_submission.ps1
```

生成位置：

```text
dist/Visus-submission-source.zip
```

## 7. 演示流程建议

1. 启动后端，展示终端中 `Backend URL: http://localhost:8081`。
2. 手机安装 APK，打开 Visus。
3. 注册/登录账号。
4. 设置后端 IP 和端口。
5. 在 AI 页输入或说“你好”，展示 AI 回复。
6. 输入“开启辅助出行”，展示自动切到辅助出行页并启动推流。
7. 输入“去附近医院”，展示地图导航页和路线规划。
8. 触发“紧急求助”，展示好友提醒或 SOS 记录。

## 8. 当前边界与后续优化

当前版本重点完成可运行演示和核心闭环，后续可继续优化：

- 接入更稳定的端侧语音识别方案，降低手机系统语音服务依赖。
- 增加真实 GPS 定位数据接入，替换部分演示默认坐标。
- 增加更多无障碍测试场景，如楼梯、门、玻璃门、低矮障碍物。
- 增加比赛演示录屏和自动化测试脚本。
- 对视觉模型进行轻量化，降低普通电脑运行压力。

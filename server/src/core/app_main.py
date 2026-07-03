# app_main.py
# -*- coding: utf-8 -*-
import os, sys, time, json, asyncio, base64, io, wave, traceback
from pathlib import Path
# 添加项目根目录到路径
sys.path.insert(0, os.path.join(os.path.dirname(__file__), '..', '..'))
sys.path.insert(0, os.path.join(os.path.dirname(__file__), '..'))

try:
    import audioop
except ModuleNotFoundError:
    from audio import audioop_shim
    import types
    audioop = types.SimpleNamespace(
        mul=audioop_shim.mul,
        tomono=audioop_shim.tomono,
        ratecv=audioop_shim.ratecv,
    )
    sys.modules['audioop'] = audioop
from typing import Any, Dict, Optional, Tuple, List, Callable, Set, Deque
from collections import deque
from dataclasses import dataclass
import re
# 在其它 import 之后加：
from voice.doubao_extractor import extract_english_label

# 重型 ML 依赖——缺失时降级运行
try:
    from core.navigation_master import NavigationMaster, OrchestratorResult
    from navigation.workflow_blindpath import BlindPathNavigator
    from navigation.workflow_crossstreet import CrossStreetNavigator
    from navigation.multimodal_alert import MultimodalAlertManager, build_multimodal_alert
    from ultralytics import YOLO
    from vision.obstacle_detector_client import ObstacleDetectorClient
    _HAS_NAVIGATION = True
except (ModuleNotFoundError, ImportError) as e:
    print(f"[WARN] Navigation modules not available: {e}")
    NavigationMaster = None
    OrchestratorResult = None
    BlindPathNavigator = None
    CrossStreetNavigator = None
    MultimodalAlertManager = None
    build_multimodal_alert = None
    YOLO = None
    ObstacleDetectorClient = None
    _HAS_NAVIGATION = False

import torch
from fastapi import FastAPI, WebSocket, WebSocketDisconnect, Request, HTTPException
from fastapi.responses import HTMLResponse, PlainTextResponse
from fastapi.staticfiles import StaticFiles
from starlette.websockets import WebSocketState
import uvicorn
import cv2
import numpy as np

try:
    import mediapipe as mp
except (ModuleNotFoundError, ImportError):
    mp = None
    print("[WARN] mediapipe not available")
from utils import bridge_io
import threading
try:
    from vision import yolomedia  # 确保和 app_main.py 同目录，文件名就是 yolomedia.py
except (ModuleNotFoundError, ImportError) as e:
    yolomedia = None
    print(f"[WARN] yolomedia not available: {e}")
# ---- Windows 事件循环策略 ----
if sys.platform.startswith("win"):
    try:
        asyncio.set_event_loop_policy(asyncio.WindowsSelectorEventLoopPolicy())
    except Exception:
        pass

# ---- .env ----
try:
    from dotenv import load_dotenv
    load_dotenv()
except Exception:
    pass

# ---- DashScope ASR 基础（仅语音识别；大模型回复已切换到豆包方舟）----
from dashscope import audio as dash_audio  # 若未安装，会在原项目里抛错提示

ASR_API_KEY = os.getenv("DASHSCOPE_API_KEY", "")
_HAS_ASR = bool(ASR_API_KEY)
if not _HAS_ASR:
    print("[ASR] DASHSCOPE_API_KEY not set — voice recognition disabled", flush=True)
else:
    print(
        f"[ASR] DASHSCOPE_API_KEY loaded={bool(ASR_API_KEY)}, "
        f"prefix={ASR_API_KEY[:3]}..., suffix={ASR_API_KEY[-4:] if len(ASR_API_KEY) >= 4 else '****'}",
        flush=True
    )

MODEL        = "paraformer-realtime-v2"
SAMPLE_RATE  = 16000
AUDIO_FMT    = "pcm"
CHUNK_MS     = 20
BYTES_CHUNK  = SAMPLE_RATE * CHUNK_MS // 1000 * 2
SILENCE_20MS = bytes(BYTES_CHUNK)
ASR_SEND_BYTES = BYTES_CHUNK * 5  # 100ms @ 16kHz/16bit/mono = 3200 bytes
ASR_PENDING_MAX_CHUNKS = 100      # 2 seconds of 20ms chunks

# ---- 引入我们的模块 ----
from voice.audio_stream import (
    register_stream_route,         # 挂 /stream.wav
    broadcast_pcm16_realtime,      # 实时向连接分发 16k PCM
    mark_audio_reply_start,
    hard_reset_audio,              # 音频+AI 播放总闸
    soft_reset_audio,              # 普通AI回复前清理旧音频但保留客户端
    BYTES_PER_20MS_16K,
    is_playing_now,
    current_ai_task,
)
from voice.omni_client import stream_chat, OmniStreamPiece
from voice.asr_core import (
    ASRCallback,
    set_current_recognition,
    stop_current_recognition,
)
from voice.asr_provider import get_asr_provider
from voice.audio_player import initialize_audio_system, play_voice_text, clear_voice_queue
from voice.doubao_tts import synthesize_to_pcm8k

# ---- 同步录制器 ----
from audio import sync_recorder
import signal
import atexit

# ---- 社交模块（好友预警 / 紧急通知） ----
from social.database import init_db
from social.api_routes import router as social_router
from social.groups import router as groups_router
from social.maps import router as maps_router
from social.ai_agent import router as agent_router
from social.messaging import router as messaging_router
from social.status_ws import handle_status_websocket

app = FastAPI()

# 注册社交 API 路由 (/api/login, /api/friends, /api/status, etc.)
app.include_router(social_router)
app.include_router(groups_router)
app.include_router(maps_router)
app.include_router(agent_router)
app.include_router(messaging_router)

ASR_PROVIDER = get_asr_provider()
AI_MIC_SUPPRESS_TAIL_SEC = float(os.getenv("AI_MIC_SUPPRESS_TAIL_SEC", "0.8"))

APP_FILE = Path(__file__).resolve()
SRC_DIR = APP_FILE.parents[1]
SERVER_DIR = APP_FILE.parents[2]

STATIC_CANDIDATES = [
    SRC_DIR / "web" / "static",
    SERVER_DIR / "web" / "static",
]
STATIC_DIR = next((p for p in STATIC_CANDIDATES if p.exists()), None)
if STATIC_DIR is None:
    STATIC_DIR = SERVER_DIR / "web" / "static"
    STATIC_DIR.mkdir(parents=True, exist_ok=True)
    print(f"[WARN] static directory not found, created empty static dir: {STATIC_DIR}", flush=True)

TEMPLATE_CANDIDATES = [
    SRC_DIR / "web" / "templates" / "index.html",
    SERVER_DIR / "web" / "templates" / "index.html",
]
INDEX_TEMPLATE = next((p for p in TEMPLATE_CANDIDATES if p.exists()), None)
if INDEX_TEMPLATE is None:
    print(f"[WARN] index template not found in candidates: {TEMPLATE_CANDIDATES}", flush=True)

# ====== 状态与容器 ======
app.mount("/static", StaticFiles(directory=str(STATIC_DIR)), name="static")

ui_clients: Dict[int, WebSocket] = {}
current_partial: str = ""
recent_finals: List[str] = []
RECENT_MAX = 50
last_frames: Deque[Tuple[float, bytes]] = deque(maxlen=10)
VISUAL_TRIGGER_WORDS = (
    "帮我看", "看看", "识别", "这是什么", "前面有什么", "周围有什么",
    "拍照", "看一下", "能不能吃", "读一下",
    "前面", "前方", "面前", "眼前", "左边", "右边", "旁边",
    "有没有", "有一个", "有一扇", "有一把", "是什么", "在哪",
    "门", "椅子", "桌子", "瓶子", "水", "障碍物", "路况",
)
SAFETY_VISUAL_TRIGGER_WORDS = (
    "走", "能走", "可以走", "过去", "能过去", "前方", "前面", "路况",
    "安全", "危险", "障碍", "障碍物", "有没有东西", "挡路", "这里",
    "那里", "下去", "继续走", "往前走", "能不能走", "可以过去吗",
    "这里安全吗", "前面安全吗", "可以往前走吗", "前面有什么",
    "有没有障碍物", "能不能过去", "帮我看看路况", "现在能走吗",
    "前方情况怎么样",
)
VISUAL_FRAME_MAX_AGE_SEC = float(os.getenv("VISUS_VISUAL_FRAME_MAX_AGE_SEC", "5.0"))
SAFETY_SUMMARY_MAX_AGE_SEC = float(os.getenv("VISUS_SAFETY_SUMMARY_MAX_AGE_SEC", "1.0"))
CAMERA_DISPLAY_ROTATE_DEG = int(os.getenv("CAMERA_DISPLAY_ROTATE_DEG", "90"))

camera_viewers: Set[WebSocket] = set()
mobile_camera_ws: Optional[WebSocket] = None
mobile_audio_ws: Optional[WebSocket] = None

# 【新增】盲道导航相关全局变量
blind_path_navigator = None
navigation_active = False
yolo_seg_model = None
obstacle_detector = None

# 【新增】过马路导航相关全局变量
cross_street_navigator = None
cross_street_active = False
orchestrator = None  # 新增
safety_alert_manager = MultimodalAlertManager() if MultimodalAlertManager else None
safety_last_detection_frame = 0
safety_last_detection_time = 0.0
safety_last_alert_time = 0.0
safety_monitor_task: Optional[asyncio.Task] = None
latest_camera_frame: Optional[np.ndarray] = None
latest_camera_frame_ts = 0.0
latest_camera_frame_seq = 0
latest_camera_frame_size = 0
latest_safety_summary: Optional[Dict[str, Any]] = None
_safety_last_diag_log: Dict[str, float] = {}

# 【新增】omni对话状态标志
omni_conversation_active = False  # 标记omni对话是否正在进行
omni_previous_nav_state = None  # 保存omni激活前的导航状态，用于恢复

# 【新增】模型加载函数
def load_navigation_models():
    """加载盲道导航所需的模型"""
    global yolo_seg_model, obstacle_detector

    try:
        seg_model_path = os.getenv("BLIND_PATH_MODEL", os.path.join("assets", "models", "yolo-seg.pt"))
        #print(f"[NAVIGATION] 尝试加载模型: {seg_model_path}")

        if os.path.exists(seg_model_path):
            print(f"[NAVIGATION] 模型文件存在，开始加载...")
            yolo_seg_model = YOLO(seg_model_path)

            # 强制放到 GPU
            if torch.cuda.is_available():
                yolo_seg_model.to("cuda")
                print(f"[NAVIGATION] 盲道分割模型加载成功并放到GPU: {yolo_seg_model.device}")
            else:
                print("[NAVIGATION] CUDA不可用，模型仍在CPU")

            # 测试模型是否能正常运行
            try:
                test_img = np.zeros((640, 640, 3), dtype=np.uint8)
                results = yolo_seg_model.predict(
                    test_img,
                    device="cuda" if torch.cuda.is_available() else "cpu",
                    verbose=False
                )
                print(f"[NAVIGATION] 模型测试成功，支持的类别数: {len(yolo_seg_model.names) if hasattr(yolo_seg_model, 'names') else '未知'}")
                if hasattr(yolo_seg_model, 'names'):
                    print(f"[NAVIGATION] 模型类别: {yolo_seg_model.names}")
            except Exception as e:
                print(f"[NAVIGATION] 模型测试失败: {e}")
        else:
            print(f"[NAVIGATION] 错误：找不到模型文件: {seg_model_path}")
            print(f"[NAVIGATION] 当前工作目录: {os.getcwd()}")
            print(f"[NAVIGATION] 请检查文件路径是否正确")
            
        # 【修改开始】使用 ObstacleDetectorClient 替代直接的 YOLO
        obstacle_model_path = os.getenv("OBSTACLE_MODEL", os.path.join("assets", "models", "yoloe-11l-seg.pt"))
        print(f"[NAVIGATION] 尝试加载障碍物检测模型: {obstacle_model_path}")
        
        # 跳过障碍物检测器加载，避免 CLIP 权重下载阻塞（需 572MB 下载且 GitHub 不通）
        SKIP_OBSTACLE = os.getenv("SKIP_OBSTACLE_DETECTOR", "1")
        if os.path.exists(obstacle_model_path) and SKIP_OBSTACLE != "1":
            print(f"[NAVIGATION] 障碍物检测模型文件存在，开始加载...")
            try:
                import socket
                old_timeout = socket.getdefaulttimeout()
                socket.setdefaulttimeout(8)
                obstacle_detector = ObstacleDetectorClient(model_path=obstacle_model_path)
                socket.setdefaulttimeout(old_timeout)
                print(f"[NAVIGATION] ========== YOLO-E 障碍物检测器加载成功 ==========")
                
                # 检查模型是否成功加载
                if hasattr(obstacle_detector, 'model') and obstacle_detector.model is not None:
                    print(f"[NAVIGATION] YOLO-E 模型已初始化")
                    print(f"[NAVIGATION] 模型设备: {next(obstacle_detector.model.parameters()).device}")
                else:
                    print(f"[NAVIGATION] 警告：YOLO-E 模型初始化异常")
                
                # 检查白名单是否成功加载
                if hasattr(obstacle_detector, 'WHITELIST_CLASSES'):
                    print(f"[NAVIGATION] 白名单类别数: {len(obstacle_detector.WHITELIST_CLASSES)}")
                    print(f"[NAVIGATION] 白名单前10个类别: {', '.join(obstacle_detector.WHITELIST_CLASSES[:10])}")
                else:
                    print(f"[NAVIGATION] 警告：白名单类别未定义")
                
                # 检查文本特征是否成功预计算
                if hasattr(obstacle_detector, 'whitelist_embeddings') and obstacle_detector.whitelist_embeddings is not None:
                    print(f"[NAVIGATION] YOLO-E 文本特征已预计算")
                    print(f"[NAVIGATION] 文本特征张量形状: {obstacle_detector.whitelist_embeddings.shape if hasattr(obstacle_detector.whitelist_embeddings, 'shape') else '未知'}")
                else:
                    print(f"[NAVIGATION] 警告：YOLO-E 文本特征未预计算")
                
                # 测试障碍物检测功能
                print(f"[NAVIGATION] 开始测试 YOLO-E 检测功能...")
                try:
                    test_img = np.zeros((640, 640, 3), dtype=np.uint8)
                    # 在测试图像中画一个白色矩形，模拟一个物体
                    cv2.rectangle(test_img, (200, 200), (400, 400), (255, 255, 255), -1)
                    
                    # 测试检测（不提供 path_mask）
                    test_results = obstacle_detector.detect(test_img)
                    print(f"[NAVIGATION] YOLO-E 检测测试成功!")
                    print(f"[NAVIGATION] 测试检测结果数: {len(test_results)}")
                    
                    if len(test_results) > 0:
                        print(f"[NAVIGATION] 测试检测到的物体:")
                        for i, obj in enumerate(test_results):
                            print(f"  - 物体 {i+1}: {obj.get('name', 'unknown')}, "
                                  f"面积比例: {obj.get('area_ratio', 0):.3f}, "
                                  f"位置: ({obj.get('center_x', 0):.0f}, {obj.get('center_y', 0):.0f})")
                except Exception as e:
                    print(f"[NAVIGATION] YOLO-E 检测测试失败: {e}")
                    import traceback
                    traceback.print_exc()
                
                print(f"[NAVIGATION] ========== YOLO-E 障碍物检测器加载完成 ==========")
                
            except Exception as e:
                print(f"[NAVIGATION] 障碍物检测器加载失败: {e}")
                import traceback
                traceback.print_exc()
                obstacle_detector = None
        else:
            print(f"[NAVIGATION] 警告：找不到障碍物检测模型文件: {obstacle_model_path}")
        
    except Exception as e:
        print(f"[NAVIGATION] 模型加载失败: {e}")
        import traceback
        traceback.print_exc()

# 在程序启动时加载模型
print("[NAVIGATION] 开始加载导航模型...")
load_navigation_models()
print(f"[NAVIGATION] 模型加载完成 - yolo_seg_model: {yolo_seg_model is not None}")

# 【新增】启动同步录制
print("[RECORDER] 启动同步录制系统...")
sync_recorder.start_recording()
print("[RECORDER] 录制系统已启动，将自动保存视频和音频")

# 【新增】注册退出处理器，确保Ctrl+C时保存录制文件
def cleanup_on_exit():
    """程序退出时的清理工作"""
    print("\n[SYSTEM] 正在关闭录制器...")
    try:
        sync_recorder.stop_recording()
        print("[SYSTEM] 录制文件已保存")
    except Exception as e:
        print(f"[SYSTEM] 关闭录制器时出错: {e}")

def signal_handler(sig, frame):
    """处理Ctrl+C信号"""
    print("\n[SYSTEM] 收到中断信号，正在安全退出...")
    cleanup_on_exit()
    import sys
    sys.exit(0)

# 注册信号处理器
signal.signal(signal.SIGINT, signal_handler)  # Ctrl+C
signal.signal(signal.SIGTERM, signal_handler)  # 终止信号
atexit.register(cleanup_on_exit)  # 正常退出时也调用

print("[RECORDER] 已注册退出处理器 - Ctrl+C时会自动保存录制文件")

# ============== 关键：系统级"硬重置"总闸 =================
interrupt_lock = asyncio.Lock()

# ============== YOLO媒体线程管理 =================
yolomedia_thread: Optional[threading.Thread] = None
yolomedia_stop_event = threading.Event()
yolomedia_running = False
yolomedia_sending_frames = False  # 新增：标记YOLO是否已经开始发送处理后的帧

# 物品名称到YOLO类别的映射
ITEM_TO_CLASS_MAP = {
    "红牛": "Red_Bull",
    "AD钙奶": "AD_milk",
    "ad钙奶": "AD_milk",
    "钙奶": "AD_milk",
}

async def ui_broadcast_raw(msg: str):
    dead = []
    for k, ws in list(ui_clients.items()):
        try:
            await ws.send_text(msg)
        except Exception:
            dead.append(k)
    for k in dead:
        ui_clients.pop(k, None)


async def ui_broadcast_partial(text: str):
    global current_partial
    current_partial = text
    await ui_broadcast_raw("PARTIAL:" + text)

async def ui_broadcast_final(text: str):
    global current_partial, recent_finals
    current_partial = ""
    recent_finals.append(text)
    if len(recent_finals) > RECENT_MAX:
        recent_finals = recent_finals[-RECENT_MAX:]
    await ui_broadcast_raw("FINAL:" + text)
    print(f"[ASR/AI FINAL] {text}", flush=True)

async def ui_broadcast_ai_reply(text: str, tts_fallback: bool = False):
    global current_partial, recent_finals
    display_text = "[AI] " + text.strip()
    current_partial = ""
    if display_text.strip():
        recent_finals.append(display_text)
        if len(recent_finals) > RECENT_MAX:
            recent_finals = recent_finals[-RECENT_MAX:]
    payload = {
        "type": "ai_reply",
        "text": text,
        "tts_fallback": bool(tts_fallback),
    }
    await ui_broadcast_raw(json.dumps(payload, ensure_ascii=False))

async def ui_broadcast_status(stage: str):
    payload = {"type": "status", "stage": stage}
    await ui_broadcast_raw(json.dumps(payload, ensure_ascii=False))

async def ui_broadcast_multimodal_alert(alert: dict):
    if not alert:
        return
    try:
        payload = dict(alert)
        payload["type"] = "multimodal_alert"
        if len(ui_clients) == 0:
            print("[MULTIMODAL_ALERT] no ui clients connected", flush=True)
        await ui_broadcast_raw(json.dumps(payload, ensure_ascii=False))
        print(f"[MULTIMODAL_ALERT] broadcast clients={len(ui_clients)}", flush=True)
        print(
            f"[MULTIMODAL_ALERT] broadcast to {len(ui_clients)} ui clients: "
            f"level={payload.get('level')} text={payload.get('text')}",
            flush=True
        )
    except Exception as e:
        print(f"[MULTIMODAL_ALERT] broadcast failed: {repr(e)}", flush=True)


def update_latest_camera_frame(bgr: np.ndarray, raw_size: int, frame_counter: int):
    global latest_camera_frame, latest_camera_frame_ts, latest_camera_frame_seq, latest_camera_frame_size
    latest_camera_frame = bgr.copy()
    latest_camera_frame_ts = time.time()
    latest_camera_frame_seq = frame_counter
    latest_camera_frame_size = raw_size
    if frame_counter <= 3 or frame_counter % 30 == 0:
        print(f"[SAFETY_MONITOR] latest frame updated size={raw_size}", flush=True)


def _fake_obstacles_for_frame(bgr: np.ndarray):
    h, w = bgr.shape[:2]
    return [{
        "name": "box",
        "area_ratio": 0.12,
        "center_x": w * 0.5,
        "center_y": h * 0.7,
        "bottom_y_ratio": 0.88,
        "confidence": 1.0,
    }]


def safety_log_limited(key: str, message: str, interval_sec: float = 2.0):
    now = time.time()
    last = _safety_last_diag_log.get(key, 0.0)
    if now - last >= interval_sec:
        _safety_last_diag_log[key] = now
        print(message, flush=True)


async def _emit_safety_alert(alert: dict, detect_start: float):
    global safety_last_alert_time
    safety_last_alert_time = time.time()
    level = str(alert.get("level", "medium"))
    text = alert.get("text", "")

    if level == "critical":
        print("[AUDIO_QUEUE] clear for critical alert", flush=True)
        try:
            clear_voice_queue("for critical alert")
            await soft_reset_audio("critical safety alert")
        except Exception as e:
            print(f"[AUDIO_QUEUE] clear failed for critical alert: {repr(e)}", flush=True)

    try:
        print(f"[AUDIO_QUEUE] enqueue safety alert level={level} text={text}", flush=True)
        tts_start = time.perf_counter()
        play_voice_text(text)
        print(f"[PERF_TTS] safety_tts_ms={(time.perf_counter() - tts_start) * 1000:.1f}", flush=True)
    except Exception as e:
        print(f"[MULTIMODAL_ALERT] voice failed: {repr(e)}", flush=True)

    await ui_broadcast_multimodal_alert(alert)
    print(f"[PERF] safety_alert_latency_ms={(time.perf_counter() - detect_start) * 1000:.1f}", flush=True)


async def safety_monitor_loop():
    if os.getenv("VISUS_SAFETY_ALWAYS_ON", "0") != "1":
        print("[SAFETY_MONITOR] always-on monitor disabled; set VISUS_SAFETY_ALWAYS_ON=1 to enable", flush=True)
        return
    if safety_alert_manager is None:
        print("[SAFETY_MONITOR] detector disabled", flush=True)
        return

    interval_ms = max(300.0, min(800.0, float(os.getenv("VISUS_SAFETY_MONITOR_INTERVAL_MS", "800"))))
    fake_enabled = os.getenv("VISUS_FAKE_OBSTACLE", "0") == "1"
    last_fake_ts = 0.0
    last_seq = -1
    print("[SAFETY_MONITOR] started", flush=True)
    print("[SAFETY_MONITOR] camera connected, starting always-on monitor", flush=True)

    try:
        while True:
            await asyncio.sleep(interval_ms / 1000.0)
            frame = latest_camera_frame
            frame_ts = latest_camera_frame_ts
            frame_seq = latest_camera_frame_seq
            if frame is None or frame_ts <= 0:
                safety_log_limited("no_frame", "[SAFETY_MONITOR] tick no latest frame")
                continue

            age_ms = (time.time() - frame_ts) * 1000.0
            safety_log_limited("tick", f"[SAFETY_MONITOR] tick frame_age_ms={age_ms:.1f}", 1.0)
            if frame_seq == last_seq and age_ms > 1500:
                continue
            last_seq = frame_seq

            detect_start = time.perf_counter()
            h, w = frame.shape[:2]

            if fake_enabled:
                now = time.time()
                if now - last_fake_ts < 2.0:
                    continue
                last_fake_ts = now
                obstacles = _fake_obstacles_for_frame(frame)
                print(f"[SAFETY_MONITOR] fake obstacle generated count={len(obstacles)}", flush=True)
            else:
                if os.getenv("SKIP_OBSTACLE_DETECTOR", "1") == "1":
                    safety_log_limited("skip_detector", "[SAFETY_MONITOR] real detector skipped by SKIP_OBSTACLE_DETECTOR=1")
                    safety_log_limited("skip_detector_detail", "[DETECTOR] enabled=false model_loaded=false reason=SKIP_OBSTACLE_DETECTOR=1")
                    continue
                if obstacle_detector is None:
                    safety_log_limited("detector_disabled", "[SAFETY_MONITOR] detector disabled")
                    safety_log_limited("detector_disabled_detail", "[DETECTOR] enabled=false model_loaded=false")
                    continue
                try:
                    print("[DETECTOR] enabled=true model_loaded=true", flush=True)
                    obstacles = await asyncio.to_thread(obstacle_detector.detect, frame)
                except Exception as e:
                    print(f"[SAFETY_MONITOR] detector failed: {repr(e)}", flush=True)
                    print(f"[DETECTOR] no obstacle after filtering reason=detector_failed:{repr(e)}", flush=True)
                    continue

            obstacles = obstacles or []
            print(f"[DETECTOR] raw_count={len(obstacles)}", flush=True)
            for obs in obstacles:
                print(
                    f"[DETECTOR] obs name={obs.get('name')} conf={obs.get('confidence')} "
                    f"area_ratio={obs.get('area_ratio')} bottom_y_ratio={obs.get('bottom_y_ratio')} "
                    f"center_x={obs.get('center_x')}",
                    flush=True,
                )
            print(f"[SAFETY_MONITOR] detected obstacles count={len(obstacles)}", flush=True)
            if not obstacles:
                safety_log_limited("no_obstacles", "[SAFETY_MONITOR] no obstacles from detector")
                safety_log_limited("no_obstacles_detail", "[DETECTOR] no obstacle after filtering reason=empty_result")

            alert_candidates = []
            if build_multimodal_alert:
                for obs in obstacles:
                    alert_candidate = build_multimodal_alert(obs, w, h)
                    if alert_candidate:
                        alert_candidates.append(alert_candidate)
            print(f"[DETECTOR] filtered_count={len(alert_candidates)}", flush=True)
            chosen_alert = None
            if alert_candidates:
                level_rank = {"low": 1, "medium": 2, "high": 3, "critical": 4}
                chosen_alert = max(
                    alert_candidates,
                    key=lambda item: (
                        level_rank.get(item.get("level", "low"), 0),
                        float(item.get("bottom_y_ratio", 0.0)),
                        float(item.get("area_ratio", 0.0)),
                    ),
                )
                print(f"[DETECTOR] chosen_obstacle={json.dumps(chosen_alert, ensure_ascii=False)}", flush=True)
            elif obstacles:
                print("[DETECTOR] no obstacle after filtering reason=below_alert_threshold", flush=True)

            update_latest_safety_summary(obstacles, w, h, chosen_alert)

            alert = safety_alert_manager.process_obstacles(obstacles, w, h)
            if alert:
                print(f"[SAFETY_MONITOR] alert emitted level={alert.get('level')}", flush=True)
                await _emit_safety_alert(alert, detect_start)
            else:
                print("[SAFETY_MONITOR] alert skipped by cooldown", flush=True)
    except asyncio.CancelledError:
        print("[SAFETY_MONITOR] camera disconnected, stopping always-on monitor", flush=True)
        raise
    except Exception as e:
        print(f"[SAFETY_MONITOR] loop failed: {repr(e)}", flush=True)

def should_attach_image(user_text: str) -> bool:
    text = (user_text or "").strip()
    if not text:
        return False
    if any(word in text for word in SAFETY_VISUAL_TRIGGER_WORDS):
        print("[VISION_QA] safety_question=true attach_image=true reason=safety_keyword", flush=True)
        return True
    return any(word in text for word in VISUAL_TRIGGER_WORDS)


def is_safety_question(user_text: str) -> bool:
    text = (user_text or "").strip()
    if not text:
        return False
    matched = next((word for word in SAFETY_VISUAL_TRIGGER_WORDS if word in text), None)
    if matched:
        print(f"[SAFETY_QA] safety_question=true reason={matched}", flush=True)
        return True
    return False


def _summary_is_fresh(summary: Optional[Dict[str, Any]], max_age_sec: float = SAFETY_SUMMARY_MAX_AGE_SEC) -> bool:
    if not summary:
        return False
    ts = float(summary.get("ts", 0.0) or 0.0)
    return ts > 0 and (time.time() - ts) <= max_age_sec


def update_latest_safety_summary(obstacles, frame_width: int, frame_height: int, chosen_alert: Optional[dict] = None):
    global latest_safety_summary
    now = time.time()
    if chosen_alert:
        latest_safety_summary = {
            "ts": now,
            "frame_age_ms": (now - latest_camera_frame_ts) * 1000.0 if latest_camera_frame_ts else None,
            "has_obstacle": True,
            "level": chosen_alert.get("level"),
            "direction": chosen_alert.get("direction"),
            "obstacle": chosen_alert.get("obstacle"),
            "obstacle_cn": chosen_alert.get("obstacle_cn"),
            "text": chosen_alert.get("text"),
            "bottom_y_ratio": chosen_alert.get("bottom_y_ratio"),
            "area_ratio": chosen_alert.get("area_ratio"),
            "alert": dict(chosen_alert),
        }
    elif obstacles:
        latest_safety_summary = {
            "ts": now,
            "frame_age_ms": (now - latest_camera_frame_ts) * 1000.0 if latest_camera_frame_ts else None,
            "has_obstacle": False,
            "text": "当前没有检测到明显障碍，但请慢慢前进，我会继续观察。",
            "raw_count": len(obstacles),
        }
    else:
        latest_safety_summary = {
            "ts": now,
            "frame_age_ms": (now - latest_camera_frame_ts) * 1000.0 if latest_camera_frame_ts else None,
            "has_obstacle": False,
            "text": "当前没有检测到明显障碍，但请慢慢前进，我会继续观察。",
            "raw_count": 0,
        }
    print(f"[SAFETY_MONITOR] latest_safety_summary={json.dumps(latest_safety_summary, ensure_ascii=False)}", flush=True)


def get_latest_camera_jpeg(max_age_sec: float = VISUAL_FRAME_MAX_AGE_SEC) -> Optional[bytes]:
    if not last_frames:
        return None
    ts, jpeg_bytes = last_frames[-1]
    if time.time() - ts > max_age_sec:
        return None
    return jpeg_bytes


def _rotate_bgr_for_display(bgr):
    if bgr is None:
        return None
    deg = CAMERA_DISPLAY_ROTATE_DEG % 360
    if deg == 90:
        return cv2.rotate(bgr, cv2.ROTATE_90_CLOCKWISE)
    if deg == 180:
        return cv2.rotate(bgr, cv2.ROTATE_180)
    if deg == 270:
        return cv2.rotate(bgr, cv2.ROTATE_90_COUNTERCLOCKWISE)
    return bgr

def compress_camera_jpeg(jpeg_bytes: bytes, max_side: int = 640, quality: int = 70) -> bytes:
    arr = np.frombuffer(jpeg_bytes, dtype=np.uint8)
    bgr = cv2.imdecode(arr, cv2.IMREAD_COLOR)
    if bgr is None or bgr.size == 0:
        return jpeg_bytes
    bgr = _rotate_bgr_for_display(bgr)
    h, w = bgr.shape[:2]
    scale = min(1.0, float(max_side) / float(max(h, w)))
    if scale < 1.0:
        bgr = cv2.resize(bgr, (max(1, int(w * scale)), max(1, int(h * scale))), interpolation=cv2.INTER_AREA)
    ok, enc = cv2.imencode(".jpg", bgr, [int(cv2.IMWRITE_JPEG_QUALITY), int(quality)])
    return enc.tobytes() if ok else jpeg_bytes

def encode_viewer_jpeg(bgr, quality: int = 80) -> Optional[bytes]:
    if bgr is None:
        return None
    display_bgr = _rotate_bgr_for_display(bgr)
    ok, enc = cv2.imencode(".jpg", display_bgr, [int(cv2.IMWRITE_JPEG_QUALITY), int(quality)])
    return enc.tobytes() if ok else None

async def full_system_reset(reason: str = ""):
    """
    回到刚启动后的状态：
    1) 停播 + 取消AI任务 + 切断所有/stream.wav（hard_reset_audio）
    2) 停止 ASR 实时识别流（关键）
    3) 清 UI 状态
    4) 清最近相机帧（避免把旧帧又拼进下一轮）
    5) 通知移动端：RESET（可选）
    """
    # 1) 音频&AI
    await hard_reset_audio(reason or "full_system_reset")

    # 2) ASR
    await stop_current_recognition()

    # 3) UI
    global current_partial, recent_finals
    current_partial = ""
    recent_finals = []
    await ui_broadcast_status("idle")

    # 4) 相机帧
    try:
        last_frames.clear()
    except Exception:
        pass

    print("[SYSTEM] full reset done.", flush=True)

# ========= 启动/停止 YOLO 媒体处理 =========
def start_yolomedia_with_target(target_name: str):
    """启动yolomedia线程，搜索指定物品"""
    global yolomedia_thread, yolomedia_stop_event, yolomedia_running, yolomedia_sending_frames
    
    # 如果已经在运行，先停止
    if yolomedia_running:
        stop_yolomedia()
    
    # 查找对应的YOLO类别
    yolo_class = ITEM_TO_CLASS_MAP.get(target_name, target_name)
    print(f"[YOLOMEDIA] Starting with target: {target_name} -> YOLO class: {yolo_class}", flush=True)
    print(f"[YOLOMEDIA] Available mappings: {ITEM_TO_CLASS_MAP}", flush=True)  # 添加这行调试
    
    yolomedia_stop_event.clear()
    yolomedia_running = True
    yolomedia_sending_frames = False  # 重置发送帧状态
    
    def _run():
        try:
            # 传递目标类别名和停止事件
            yolomedia.main(headless=True, prompt_name=yolo_class, stop_event=yolomedia_stop_event)
        except Exception as e:
            print(f"[YOLOMEDIA] worker stopped: {e}", flush=True)
        finally:
            global yolomedia_running, yolomedia_sending_frames
            yolomedia_running = False
            yolomedia_sending_frames = False
    
    yolomedia_thread = threading.Thread(target=_run, daemon=True)
    yolomedia_thread.start()
    print(f"[YOLOMEDIA] background worker started for: {yolo_class}（正在初始化，暂时显示原始画面）", flush=True)

def stop_yolomedia():
    """停止yolomedia线程"""
    global yolomedia_thread, yolomedia_stop_event, yolomedia_running, yolomedia_sending_frames
    
    if yolomedia_running:
        print("[YOLOMEDIA] Stopping worker...", flush=True)
        yolomedia_stop_event.set()
        
        # 等待线程结束（最多等5秒）
        if yolomedia_thread and yolomedia_thread.is_alive():
            yolomedia_thread.join(timeout=5.0)
        
        yolomedia_running = False
        yolomedia_sending_frames = False
        
        # 【新增】如果orchestrator在找物品模式，结束时不自动恢复（由命令控制）
        # 只清理标志位即可
        print("[YOLOMEDIA] Worker stopped, 等待状态切换.", flush=True)

# ========= 自定义的 start_ai_with_text，支持识别特殊命令 =========
async def start_ai_with_text_custom(user_text: str):
    """扩展版的AI启动函数，支持识别特殊命令"""
    global navigation_active, blind_path_navigator, cross_street_active, cross_street_navigator, orchestrator
    
    # 导航模式下只有特定词才进入 omni 对话，避免导航语音误触发普通 AI。
    if orchestrator:
        current_state = orchestrator.get_state()
        if current_state not in ["CHAT", "IDLE"]:
            # 检查是否是允许的对话触发词
            allowed_keywords = ["帮我看", "帮我看下", "帮我找", "找一下", "看看", "识别一下"]
            is_allowed_query = any(keyword in user_text for keyword in allowed_keywords)
            
            # 检查是否是导航控制命令
            nav_control_keywords = ["开始过马路", "过马路结束", "开始导航", "盲道导航", "停止导航", "结束导航", 
                                   "停止检测"]
            is_nav_control = any(keyword in user_text for keyword in nav_control_keywords)
            
            # 如果既不是允许的查询，也不是导航控制命令，则丢弃
            if not is_allowed_query and not is_nav_control:
                print(f"[导航模式] 丢弃非对话语音: {user_text}")
                return  # 直接丢弃，不进入omni
    
    # 【修改】检查是否是过马路相关命令 - 使用orchestrator控制
    if "开始过马路" in user_text or "帮我过马路" in user_text:
        # 【新增】如果正在找物品，先停止
        if yolomedia_running:
            stop_yolomedia()
            print("[ITEM_SEARCH] 从找物品模式切换到过马路")
        
        if orchestrator:
            orchestrator.start_crossing()
            print(f"[CROSS_STREET] 过马路模式已启动，状态: {orchestrator.get_state()}")
            # 播放启动语音并广播到UI
            play_voice_text("过马路模式已启动。")
            await ui_broadcast_final("[系统] 过马路模式已启动")
        else:
            print("[CROSS_STREET] 警告：导航统领器未初始化！")
            play_voice_text("启动过马路模式失败，请稍后重试。")
            await ui_broadcast_final("[系统] 导航系统未就绪")
        return
    
    if "过马路结束" in user_text or "结束过马路" in user_text:
        if orchestrator:
            orchestrator.stop_navigation()
            print(f"[CROSS_STREET] 导航已停止，状态: {orchestrator.get_state()}")
            # 播放停止语音并广播到UI
            play_voice_text("已停止导航。")
            await ui_broadcast_final("[系统] 过马路模式已停止")
        else:
            await ui_broadcast_final("[系统] 导航系统未运行")
        return
    
    # 【修改】检查是否是导航相关命令 - 使用orchestrator控制
    if "开始导航" in user_text or "盲道导航" in user_text or "帮我导航" in user_text:
        # 【新增】如果正在找物品，先停止
        if yolomedia_running:
            stop_yolomedia()
            print("[ITEM_SEARCH] 从找物品模式切换到盲道导航")
        
        if orchestrator:
            orchestrator.start_blind_path_navigation()
            print(f"[NAVIGATION] 盲道导航已启动，状态: {orchestrator.get_state()}")
            await ui_broadcast_final("[系统] 盲道导航已启动")
        else:
            print("[NAVIGATION] 警告：导航统领器未初始化！")
            await ui_broadcast_final("[系统] 导航系统未就绪")
        return
    
    if "停止导航" in user_text or "结束导航" in user_text:
        if orchestrator:
            orchestrator.stop_navigation()
            print(f"[NAVIGATION] 导航已停止，状态: {orchestrator.get_state()}")
            await ui_broadcast_final("[系统] 盲道导航已停止")
        else:
            await ui_broadcast_final("[系统] 导航系统未运行")
        return

    nav_cmd_keywords = ["开始过马路", "过马路结束", "开始导航", "盲道导航", "停止导航", "结束导航", "立即通过", "现在通过", "继续"]
    if any(k in user_text for k in nav_cmd_keywords):
        if orchestrator:
            orchestrator.on_voice_command(user_text)
            await ui_broadcast_final("[系统] 导航模式已更新")
        else:
            await ui_broadcast_final("[系统] 导航统领器未初始化")
        return    

    # 检查是否是"帮我找/识别一下xxx"的命令
    # 扩展正则表达式，支持更多关键词
    find_pattern = r"(?:^\s*帮我)?\s*找一下\s*(.+?)(?:。|！|？|$)"
    match = re.search(find_pattern, user_text)
        
    if match:
        # 提取中文物品名称
        item_cn = match.group(1).strip()
        if item_cn:
            # 【新增】用本地映射 + 豆包提取英文类名
            label_en, src = extract_english_label(item_cn)
            print(f"[COMMAND] Finder request: '{item_cn}' -> '{label_en}' (src={src})", flush=True)

            # 【新增】切换到找物品模式（暂停导航）
            if orchestrator:
                orchestrator.start_item_search()
                print(f"[ITEM_SEARCH] 已切换到找物品模式，状态: {orchestrator.get_state()}")
            
            # 【关键】把英文类名传给 yolomedia（它会在找不到类时自动切 YOLOE）
            start_yolomedia_with_target(label_en)

            # 给前端/语音来个确认反馈
            try:
                await ui_broadcast_final(f"[找物品] 正在寻找 {item_cn}...")
            except Exception:
                pass

            return
    
    # 检查是否是"找到了"的命令
    if "找到了" in user_text or "拿到了" in user_text:
        print("[COMMAND] Found command detected", flush=True)
        # 停止yolomedia
        stop_yolomedia()
        
        # 【新增】停止找物品模式，恢复之前的导航状态
        if orchestrator:
            orchestrator.stop_item_search(restore_nav=True)
            current_state = orchestrator.get_state()
            print(f"[ITEM_SEARCH] 找物品结束，当前状态: {current_state}")
            
            # 根据恢复的状态给出反馈
            if current_state in ["BLINDPATH_NAV", "SEEKING_CROSSWALK", "WAIT_TRAFFIC_LIGHT", "CROSSING", "SEEKING_NEXT_BLINDPATH"]:
                await ui_broadcast_final("[找物品] 已找到物品，继续导航。")
            else:
                await ui_broadcast_final("[找物品] 已找到物品。")
        else:
            await ui_broadcast_final("[找物品] 已找到物品。")
        
        return
    
    # 【修改】omni对话开始时，切换到CHAT模式
    global omni_conversation_active, omni_previous_nav_state
    omni_conversation_active = True
    
    # 保存当前导航状态并切换到CHAT模式
    if orchestrator:
        current_state = orchestrator.get_state()
        # 只有在导航模式下才需要保存和切换
        if current_state not in ["CHAT", "IDLE"]:
            omni_previous_nav_state = current_state
            orchestrator.force_state("CHAT")
            print(f"[OMNI] 对话开始，从{current_state}切换到CHAT模式")
        else:
            omni_previous_nav_state = None
            print(f"[OMNI] 对话开始（当前已在{current_state}模式）")
    
    # 如果不是特殊命令，执行原有的AI对话逻辑
    # 但如果yolomedia正在运行，暂时不处理普通对话
    if yolomedia_running:
        print("[AI] YOLO media is running, skipping normal AI response", flush=True)
        return
    
    # 原有的AI对话逻辑
    await start_ai_with_text(user_text)

# ========= Omni 播放启动 =========
async def start_ai_with_text(user_text: str):
    """硬重置后，开启新的 AI 语音输出。"""
    fallback_reply = "抱歉，我刚刚没有听清，请再说一遍。"
    attach_image_request = should_attach_image(user_text)
    visual_jpeg: Optional[bytes] = None
    if attach_image_request:
        visual_jpeg = get_latest_camera_jpeg()
        if visual_jpeg is None:
            print("[AI] visual request without fresh camera frame; continuing text-only", flush=True)
            attach_image_request = False
            visual_jpeg = None

    def _to_stream_pcm(audio_bytes: bytes, rate_state):
        if not audio_bytes:
            return b"", rate_state
        try:
            if audio_bytes[:4] == b"RIFF":
                with wave.open(io.BytesIO(audio_bytes), "rb") as wav:
                    channels = wav.getnchannels()
                    sampwidth = wav.getsampwidth()
                    framerate = wav.getframerate()
                    frames = wav.readframes(wav.getnframes())
                if sampwidth != 2:
                    return b"", rate_state
                if channels == 2:
                    frames = audioop.tomono(frames, sampwidth, 1, 0)
                if framerate != 8000:
                    frames, rate_state = audioop.ratecv(frames, sampwidth, 1, framerate, 8000, rate_state)
                return audioop.mul(frames, 2, 0.60), rate_state

            # Some multimodal providers return raw PCM16 at 24 kHz.
            pcm8k, rate_state = audioop.ratecv(audio_bytes, 2, 1, 24000, 8000, rate_state)
            return audioop.mul(pcm8k, 2, 0.60), rate_state
        except Exception as e:
            print(f"[AI AUDIO] decode failed: {e}", flush=True)
            return b"", rate_state

    async def _runner():
        txt_buf: List[str] = []
        rate_state = None
        audio_sent = False
        ai_start_ts = time.perf_counter()
        mark_audio_reply_start(ai_start_ts)
        print(f"[PERF] ai_start_at={ai_start_ts:.6f}", flush=True)
        print("[PERF_AI] llm_start", flush=True)
        first_text_logged = False
        first_audio_logged = False
        first_speaking_status = False
        pcm_queue: asyncio.Queue[Optional[bytes]] = asyncio.Queue(maxsize=96)

        async def playback_worker():
            initial_buffer = bytearray()
            initial_target_bytes = 8000 * 2 * 500 // 1000  # 500ms @ 8kHz PCM16 mono
            initial_started = False
            while True:
                pcm = await pcm_queue.get()
                if pcm is None:
                    if initial_buffer:
                        await broadcast_pcm16_realtime(bytes(initial_buffer))
                        initial_buffer.clear()
                    pcm_queue.task_done()
                    break
                try:
                    if not initial_started:
                        initial_buffer.extend(pcm)
                        if len(initial_buffer) < initial_target_bytes:
                            continue
                        initial_started = True
                        await broadcast_pcm16_realtime(bytes(initial_buffer))
                        initial_buffer.clear()
                    else:
                        await broadcast_pcm16_realtime(pcm)
                finally:
                    pcm_queue.task_done()

        async def enqueue_pcm(pcm: bytes):
            if not pcm:
                return
            if pcm_queue.full():
                try:
                    pcm_queue.get_nowait()
                    pcm_queue.task_done()
                    print("[AI AUDIO] playback queue full, dropped oldest chunk", flush=True)
                except asyncio.QueueEmpty:
                    pass
            try:
                pcm_queue.put_nowait(pcm)
            except asyncio.QueueFull:
                pass

        playback_task = asyncio.create_task(playback_worker())

        # 组装（图像+文本）
        content_list = [{
            "type": "text",
            "text": (
                "你是Visus，专为视力障碍人士设计的AI助手。请用中文简洁回复，适合语音播报，1-2句话。"
            )
        }]
        attach_image = attach_image_request
        if attach_image and visual_jpeg:
            try:
                small_jpeg = compress_camera_jpeg(visual_jpeg)
                img_b64 = base64.b64encode(small_jpeg).decode("ascii")
                content_list.append({
                    "type": "image_url",
                    "image_url": {"url": f"data:image/jpeg;base64,{img_b64}"}
                })
                print(f"[AI] attach_image=true jpeg={len(visual_jpeg)} compressed={len(small_jpeg)}", flush=True)
            except Exception as e:
                print(f"[AI] image attach failed: {repr(e)}, continuing text-only", flush=True)
                attach_image = False
        else:
            print("[AI] attach_image=false", flush=True)
        content_list.append({"type": "text", "text": user_text})

        try:
            await ui_broadcast_status("thinking")
            stream_iter = stream_chat(content_list, voice="Cherry", audio_format="wav", include_audio=True).__aiter__()
            while True:
                try:
                    piece = await asyncio.wait_for(
                        stream_iter.__anext__(),
                        timeout=20.0 if not first_text_logged else 30.0,
                    )
                except StopAsyncIteration:
                    break
                except asyncio.TimeoutError:
                    if not first_text_logged:
                        print("[PERF_AI] first_token_timeout", flush=True)
                        txt_buf.clear()
                        txt_buf.append("抱歉，AI模型这次响应超时，请再问一次。")
                        break
                    raise
                # 文本增量（仅 UI）
                if piece.text_delta:
                    if not first_text_logged:
                        first_text_logged = True
                        first_token_ms = (time.perf_counter() - ai_start_ts) * 1000
                        print(f"[PERF] ai_start_to_first_text={first_token_ms:.1f} ms", flush=True)
                        print(f"[PERF] llm_first_token_ms={first_token_ms:.1f}", flush=True)
                        print(f"[PERF_AI] first_token_ms={first_token_ms:.1f}", flush=True)
                    txt_buf.append(piece.text_delta)
                    try:
                        await ui_broadcast_partial("[AI] " + "".join(txt_buf))
                    except Exception:
                        pass

                if piece.audio_b64:
                    if not first_audio_logged:
                        first_audio_logged = True
                        print(f"[PERF] ai_start_to_first_audio={(time.perf_counter() - ai_start_ts) * 1000:.1f} ms", flush=True)
                    try:
                        audio_bytes = base64.b64decode(piece.audio_b64)
                    except Exception:
                        audio_bytes = b""
                    pcm8k, rate_state = _to_stream_pcm(audio_bytes, rate_state)
                    if pcm8k:
                        audio_sent = True
                        if not first_speaking_status:
                            first_speaking_status = True
                            await ui_broadcast_status("speaking")
                        await enqueue_pcm(pcm8k)

        except asyncio.CancelledError:
            # 被新一轮打断
            raise
        except Exception as e:
            txt_buf.clear()
            txt_buf.append(fallback_reply)
            print(f"[AI ERROR] {e}", flush=True)
        finally:
            try:
                await pcm_queue.put(None)
                await playback_task
            except asyncio.CancelledError:
                playback_task.cancel()
                raise
            except Exception:
                pass
            # 【修改】标记omni对话结束，恢复之前的导航模式
            global omni_conversation_active, omni_previous_nav_state
            omni_conversation_active = False
            
            # 恢复之前的导航状态
            if orchestrator and omni_previous_nav_state:
                orchestrator.force_state(omni_previous_nav_state)
                print(f"[OMNI] 对话结束，恢复到{omni_previous_nav_state}模式")
                omni_previous_nav_state = None
            else:
                print(f"[OMNI] 对话结束（无需恢复导航状态）")
            
            # 自然结束时补一帧静音，保持 /stream.wav 长连接等待下一轮
            try:
                await broadcast_pcm16_realtime(b"\x00"*BYTES_PER_20MS_16K)
            except Exception:
                pass

            final_text = ("".join(txt_buf)).strip()
            final_text = final_text or fallback_reply
            ai_speaking_until = 0.0
            if final_text and not audio_sent:
                try:
                    tts_start_ts = time.perf_counter()
                    print(f"[PERF] tts_start_ms={(tts_start_ts - ai_start_ts) * 1000:.1f}", flush=True)
                    print("[PERF_TTS] tts_start", flush=True)
                    tts_pcm = await asyncio.to_thread(synthesize_to_pcm8k, final_text)
                    if tts_pcm:
                        audio_sent = True
                        duration_sec = len(tts_pcm) / (8000 * 2)
                        ai_speaking_until = time.monotonic() + duration_sec + AI_MIC_SUPPRESS_TAIL_SEC
                        await ui_broadcast_status("speaking")
                        await broadcast_pcm16_realtime(tts_pcm)
                        tts_total_ms = (time.perf_counter() - tts_start_ts) * 1000
                        print(f"[PERF] doubao_tts_time={tts_total_ms:.1f} ms", flush=True)
                        print(f"[PERF_TTS] first_audio_ms={tts_total_ms:.1f}", flush=True)
                        print(f"[PERF_TTS] total_ms={tts_total_ms:.1f}", flush=True)
                except Exception as e:
                    print(f"[DOUBAO TTS] playback failed: {repr(e)}", flush=True)
            try:
                from voice.audio_stream import stream_clients
                has_audio_client = any(not sc.abort_event.is_set() for sc in list(stream_clients))
                await ui_broadcast_ai_reply(final_text, tts_fallback=(not audio_sent or not has_audio_client))
                if ai_speaking_until > time.monotonic():
                    await asyncio.sleep(ai_speaking_until - time.monotonic())
                await ui_broadcast_status("idle")
            except Exception as e:
                print(f"[AI UI ERROR] failed to broadcast final reply: {repr(e)}", flush=True)
            ai_total_ms = (time.perf_counter() - ai_start_ts) * 1000
            print(f"[PERF] total_reply_time={ai_total_ms:.1f} ms", flush=True)
            print(f"[PERF_AI] total_ms={ai_total_ms:.1f}", flush=True)

    # 真正启动前先硬重置，保证**绝无**旧音频残留
    await soft_reset_audio("start_ai_with_text")
    loop = asyncio.get_running_loop()
    from voice import audio_stream as _audio_stream
    task = loop.create_task(_runner())
    _audio_stream.current_ai_task = task

# ---------- 页面 / 健康 ----------
@app.get("/", response_class=HTMLResponse)
def root():
    if INDEX_TEMPLATE is None:
        return HTMLResponse("<html><body><h1>Visus server is running</h1></body></html>")
    with INDEX_TEMPLATE.open("r", encoding="utf-8") as f:
        return HTMLResponse(f.read())

@app.get("/api/health", response_class=PlainTextResponse)
def health():
    return "OK"

@app.get("/api/debug/multimodal_alert")
async def debug_multimodal_alert(level: str = "critical"):
    if os.getenv("VISUS_DEBUG_ALERT", "0") != "1":
        raise HTTPException(status_code=403, detail="debug disabled")

    normalized_level = (level or "critical").strip().lower()
    if normalized_level not in {"low", "medium", "high", "critical"}:
        normalized_level = "critical"

    text_by_level = {
        "low": "前方发现障碍物，请注意",
        "medium": "前方有障碍物，请注意避让",
        "high": "前方障碍物较近，请减速",
        "critical": "正前方障碍物很近，请立即停下",
    }
    alert = {
        "type": "multimodal_alert",
        "level": normalized_level,
        "text": text_by_level[normalized_level],
        "obstacle": "debug_obstacle",
        "obstacle_cn": "障碍物",
        "direction": "center",
        "bottom_y_ratio": 0.9 if normalized_level == "critical" else 0.75,
        "area_ratio": 0.18 if normalized_level == "critical" else 0.08,
        "speak": False,
        "vibrate": True,
    }
    await ui_broadcast_multimodal_alert(alert)
    return {"ok": True, "sent": True, "clients": len(ui_clients), "alert": alert}

# 注册 /stream.wav
register_stream_route(app)

# ---------- WebSocket：WebUI 文本（ASR/AI 状态推送） ----------
@app.websocket("/ws_ui")
async def ws_ui(ws: WebSocket):
    await ws.accept()
    ui_clients[id(ws)] = ws
    try:
        init = {"partial": current_partial, "finals": recent_finals[-10:]}
        await ws.send_text("INIT:" + json.dumps(init, ensure_ascii=False))
        while True:
            await asyncio.sleep(60)
    except WebSocketDisconnect:
        pass
    finally:
        ui_clients.pop(id(ws), None)

# ---------- WebSocket：移动端音频入口（ASR 上行） ----------
@app.websocket("/ws_audio")
async def ws_audio(ws: WebSocket):
    global mobile_audio_ws
    mobile_audio_ws = ws
    await ws.accept()
    print("\n[AUDIO] client connected")
    print("[PERF_ASR] audio_ws_connected", flush=True)
    recognition = None
    streaming = False
    last_ts = time.monotonic()
    keepalive_task: Optional[asyncio.Task] = None
    audio_sender_task: Optional[asyncio.Task] = None
    audio_frame_queue: asyncio.Queue[bytes] = asyncio.Queue(maxsize=40)
    audio_chunk_count = 0
    audio_bytes_window = 0
    audio_bytes_window_ts = time.monotonic()
    sent_frame_count = 0
    pcm_buffer = bytearray()
    pending_audio_chunks: Deque[bytes] = deque(maxlen=ASR_PENDING_MAX_CHUNKS)
    pending_first_ts: Optional[float] = None
    pending_drop_warned = False

    async def stop_audio_sender():
        nonlocal audio_sender_task
        if audio_sender_task and not audio_sender_task.done():
            if audio_sender_task is asyncio.current_task():
                audio_sender_task = None
            else:
                audio_sender_task.cancel()
                try:
                    await audio_sender_task
                except asyncio.CancelledError:
                    pass
                except Exception:
                    pass
        audio_sender_task = None
        try:
            while True:
                audio_frame_queue.get_nowait()
                audio_frame_queue.task_done()
        except asyncio.QueueEmpty:
            pass

    async def stop_rec(send_notice: Optional[str] = None):
        nonlocal recognition, streaming, keepalive_task, pcm_buffer
        if keepalive_task and not keepalive_task.done():
            keepalive_task.cancel()
            try: await keepalive_task
            except Exception: pass
        keepalive_task = None
        await stop_audio_sender()
        if recognition:
            try: recognition.stop()
            except Exception: pass
            recognition = None
        await set_current_recognition(None)
        streaming = False
        pcm_buffer.clear()
        if send_notice:
            try: await ws.send_text(send_notice)
            except Exception: pass

    async def on_sdk_error(_msg: str):
        print(f"[ASR ERROR] SDK callback error: {_msg}", flush=True)
        print("[ASR ERROR] failed to start recognition, audio will not be recognized", flush=True)
        try:
            await ui_broadcast_partial(f"[ASR ERROR] {_msg}")
        except Exception:
            pass
        await stop_rec(send_notice="RESTART")

    def parse_audio_command(raw: str) -> str:
        text = (raw or "").strip()
        if not text:
            return ""
        try:
            payload = json.loads(text)
            if isinstance(payload, dict):
                return str(payload.get("type") or payload.get("cmd") or payload.get("command") or "").strip().upper()
        except Exception:
            pass
        return text.upper()

    def buffer_pending_audio(data: bytes):
        nonlocal pending_first_ts, pending_drop_warned
        now = time.monotonic()
        if pending_first_ts is None:
            pending_first_ts = now
        if len(pending_audio_chunks) >= ASR_PENDING_MAX_CHUNKS and not pending_drop_warned:
            print("[ASR WARN] ASR not ready after 2 seconds, dropping buffered chunks", flush=True)
            pending_drop_warned = True
        pending_audio_chunks.append(data)

    def send_merged_audio_frames(data: bytes):
        nonlocal last_ts, sent_frame_count, pcm_buffer
        if not data:
            return
        pcm_buffer.extend(data)
        while len(pcm_buffer) >= ASR_SEND_BYTES:
            frame = bytes(pcm_buffer[:ASR_SEND_BYTES])
            del pcm_buffer[:ASR_SEND_BYTES]
            recognition.send_audio_frame(frame)
            sent_frame_count += 1
            last_ts = time.monotonic()
            if sent_frame_count <= 5 or sent_frame_count % 50 == 0:
                print(f"[AUDIO] sent merged frame #{sent_frame_count}: {len(frame)} bytes", flush=True)

    async def flush_pending_audio():
        nonlocal pending_first_ts, pending_drop_warned
        if not pending_audio_chunks:
            return
        buffered = len(pending_audio_chunks)
        print(f"[ASR] flushing {buffered} buffered audio chunks", flush=True)
        try:
            while pending_audio_chunks:
                send_merged_audio_frames(pending_audio_chunks.popleft())
        except Exception:
            await on_sdk_error("send buffered audio failed")
            return
        pending_first_ts = None
        pending_drop_warned = False

    async def audio_sender_worker():
        last_warn_ts = 0.0
        while streaming and recognition is not None:
            try:
                data = await audio_frame_queue.get()
            except asyncio.CancelledError:
                break
            try:
                send_merged_audio_frames(data)
            except Exception:
                now = time.monotonic()
                if now - last_warn_ts > 2.0:
                    print("[AUDIO WARN] send_audio_frame failed in worker", flush=True)
                    last_warn_ts = now
                await on_sdk_error("send_audio_frame failed")
                break
            finally:
                try:
                    audio_frame_queue.task_done()
                except Exception:
                    pass

    def enqueue_audio_frame(data: bytes):
        if audio_frame_queue.full():
            try:
                audio_frame_queue.get_nowait()
                audio_frame_queue.task_done()
                now = time.monotonic()
                if not hasattr(enqueue_audio_frame, "_last_warn") or now - enqueue_audio_frame._last_warn > 2.0:
                    enqueue_audio_frame._last_warn = now
                    print("[AUDIO WARN] audio frame queue full, dropped oldest frame", flush=True)
            except asyncio.QueueEmpty:
                pass
        try:
            audio_frame_queue.put_nowait(data)
        except asyncio.QueueFull:
            pass

    async def keepalive_loop():
        nonlocal last_ts, recognition, streaming
        try:
            while streaming and recognition is not None:
                idle = time.monotonic() - last_ts
                if idle > 0.25:
                    try:
                        for _ in range(10):  # ~200ms 静音，帮助ASR尽快收尾
                            recognition.send_audio_frame(SILENCE_20MS)
                        last_ts = time.monotonic()
                    except Exception:
                        await on_sdk_error("keepalive send failed")
                        return
                await asyncio.sleep(0.10)
        except asyncio.CancelledError:
            return

    try:
        while True:
            if WebSocketState and ws.client_state != WebSocketState.CONNECTED:
                break
            try:
                msg = await ws.receive()
            except WebSocketDisconnect:
                break
            except RuntimeError as e:
                if "Cannot call \"receive\"" in str(e):
                    break
                raise

            if "text" in msg and msg["text"] is not None:
                raw = (msg["text"] or "").strip()
                print(f"[AUDIO TEXT] {raw}", flush=True)
                cmd = parse_audio_command(raw)

                if cmd == "START":
                    print("[AUDIO] START received")
                    print("[PERF_ASR] ASR START received from client", flush=True)
                    await stop_rec()
                    loop = asyncio.get_running_loop()
                    def post(coro):
                        asyncio.run_coroutine_threadsafe(coro, loop)

                    # 组装 ASR 回调（把依赖都注入）
                    cb = ASRCallback(
                        on_sdk_error=lambda s: post(on_sdk_error(s)),
                        post=post,
                        ui_broadcast_partial=ui_broadcast_partial,
                        ui_broadcast_final=ui_broadcast_final,
                        is_playing_now_fn=is_playing_now,
                        start_ai_with_text_fn=start_ai_with_text_custom,  # 使用自定义版本
                        full_system_reset_fn=full_system_reset,
                        interrupt_lock=interrupt_lock,
                    )

                    try:
                        print(
                            f"[ASR] DASHSCOPE_API_KEY loaded={bool(ASR_API_KEY)}, "
                            f"prefix={ASR_API_KEY[:3]}..., suffix={ASR_API_KEY[-4:] if len(ASR_API_KEY) >= 4 else '****'}",
                            flush=True
                        )
                        if not ASR_API_KEY:
                            raise RuntimeError("DASHSCOPE_API_KEY is empty")
                        print("[ASR] creating recognition...", flush=True)
                        recognition = dash_audio.asr.Recognition(
                            api_key=ASR_API_KEY, model=MODEL, format=AUDIO_FMT,
                            sample_rate=SAMPLE_RATE, callback=cb
                        )
                        print("[ASR] recognition.start() calling...", flush=True)
                        recognition.start()
                        print("[ASR] recognition.start() returned", flush=True)
                        await set_current_recognition(recognition)
                        streaming = True
                        print("[ASR] streaming=True", flush=True)
                        last_ts = time.monotonic()
                        audio_sender_task = asyncio.create_task(audio_sender_worker())
                        keepalive_task = asyncio.create_task(keepalive_loop())
                        await ui_broadcast_partial("（已开始接收音频…）")
                        await ui_broadcast_status("listening")
                        await ws.send_text("OK:STARTED")
                        print("[PERF_ASR] ASR START sent OK:STARTED", flush=True)
                        await flush_pending_audio()
                    except Exception as e:
                        streaming = False
                        recognition = None
                        await set_current_recognition(None)
                        print(f"[ASR ERROR] failed to start recognition: {repr(e)}", flush=True)
                        traceback.print_exc()
                        print("[ASR ERROR] failed to start recognition, audio will not be recognized", flush=True)
                        try:
                            await ui_broadcast_partial(f"[ASR ERROR] failed to start recognition: {repr(e)}")
                            await ws.send_text("ERR:ASR_START_FAILED")
                        except Exception:
                            pass

                elif cmd == "STOP":
                    if recognition:
                        for _ in range(15):  # ~300ms 静音
                            try: recognition.send_audio_frame(SILENCE_20MS)
                            except Exception: break
                    await stop_rec(send_notice="OK:STOPPED")
                    await ui_broadcast_status("idle")

                elif raw.startswith("PROMPT:"):
                    # 移动端主动发起一轮：同样使用“先硬重置后播放”的强语义
                    text = raw[len("PROMPT:"):].strip()
                    if text:
                        async with interrupt_lock:
                            await start_ai_with_text_custom(text) # 使用自定义的启动函数
                        await ws.send_text("OK:PROMPT_ACCEPTED")
                    else:
                        await ws.send_text("ERR:EMPTY_PROMPT")

            elif "bytes" in msg and msg["bytes"] is not None:
                audio_chunk_count += 1
                data = msg["bytes"]
                audio_bytes_window += len(data)
                now_audio = time.monotonic()
                if now_audio - audio_bytes_window_ts >= 1.0:
                    print(f"[PERF_ASR] audio_bytes_per_sec={audio_bytes_window}", flush=True)
                    audio_bytes_window = 0
                    audio_bytes_window_ts = now_audio
                if is_playing_now():
                    if audio_chunk_count <= 5 or audio_chunk_count % 50 == 0:
                        print("[AUDIO] dropping mic chunk while AI is speaking", flush=True)
                    continue
                if audio_chunk_count <= 5 or audio_chunk_count % 50 == 0:
                    print(f"[AUDIO] chunk #{audio_chunk_count}: size={len(data)} bytes, streaming={streaming}", flush=True)
                if streaming and recognition:
                    enqueue_audio_frame(data)
                else:
                    buffer_pending_audio(data)
                    if audio_chunk_count <= 5 or audio_chunk_count % 50 == 0:
                        print("[AUDIO] chunk received before ASR streaming is ready", flush=True)

    except Exception as e:
        print(f"\n[WS ERROR] {e}")
    finally:
        await stop_rec()
        try:
            if WebSocketState is None or ws.client_state == WebSocketState.CONNECTED:
                await ws.close(code=1000)
        except Exception:
            pass
        if mobile_audio_ws is ws:
            mobile_audio_ws = None
        print("[WS] connection closed")

# ---------- WebSocket：移动端相机入口（JPEG 二进制） ----------
@app.websocket("/ws/camera")
async def ws_camera_mobile(ws: WebSocket):
    global mobile_camera_ws, blind_path_navigator, cross_street_navigator, cross_street_active, navigation_active, orchestrator, safety_monitor_task
    if mobile_camera_ws is not None:
        try:
            await mobile_camera_ws.close(code=1001, reason="new client")
        except Exception:
            pass
        mobile_camera_ws = None
    mobile_camera_ws = ws
    await ws.accept()
    print("[CAMERA] client connected")

    if os.getenv("VISUS_SAFETY_ALWAYS_ON", "0") == "1":
        if safety_monitor_task is None or safety_monitor_task.done():
            safety_monitor_task = asyncio.create_task(safety_monitor_loop())
    else:
        print("[SAFETY_MONITOR] VISUS_SAFETY_ALWAYS_ON not enabled; active safety monitor will not start", flush=True)
    
    # 【新增】初始化盲道导航器
    if blind_path_navigator is None and yolo_seg_model is not None:
        blind_path_navigator = BlindPathNavigator(yolo_seg_model, obstacle_detector)
        print("[NAVIGATION] 盲道导航器已初始化")
    else:
        if blind_path_navigator is not None:
            print("[NAVIGATION] 导航器已存在，无需重新初始化")
        elif yolo_seg_model is None:
            print("[NAVIGATION] 警告：YOLO模型未加载，无法初始化导航器")
    
    # 【新增】初始化过马路导航器
    if cross_street_navigator is None:
        if yolo_seg_model:
            cross_street_navigator = CrossStreetNavigator(
                seg_model=yolo_seg_model,
                coco_model=None,  # 不使用交通灯检测
                obs_model=None    # 暂时也不用障碍物检测，让它更快
            )
            print("[CROSS_STREET] 过马路导航器已初始化（简化版 - 仅斑马线检测）")
        else:
            print("[CROSS_STREET] 错误：缺少分割模型，无法初始化过马路导航器")
            
            if not yolo_seg_model:
                print("[CROSS_STREET] - 缺少分割模型 (yolo_seg_model)")
            if not obstacle_detector:
                print("[CROSS_STREET] - 缺少障碍物检测器 (obstacle_detector)")
    
    if orchestrator is None and blind_path_navigator is not None and cross_street_navigator is not None:
        orchestrator = NavigationMaster(blind_path_navigator, cross_street_navigator)
        print("[NAV MASTER] 统领状态机已初始化（托管模式）")
    frame_counter = 0  # 添加帧计数器
    
    try:
        while True:
            msg = await ws.receive()
            if "bytes" in msg and msg["bytes"] is not None:
                data = msg["bytes"]
                frame_counter += 1
                
                # 【新增】录制原始帧
                try:
                    sync_recorder.record_frame(data)
                except Exception as e:
                    if frame_counter % 100 == 0:  # 避免日志刷屏
                        print(f"[RECORDER] 录制帧失败: {e}")
                
                try:
                    last_frames.append((time.time(), data))
                except Exception:
                    pass
                
                # 推送到bridge_io（供yolomedia使用）
                bridge_io.push_raw_jpeg(data)
                
                # 【调试】检查导航条件
                if frame_counter % 30 == 0:  # 每30帧输出一次
                    state_dbg = orchestrator.get_state() if orchestrator else "N/A"
                    print(f"[NAVIGATION DEBUG] 帧:{frame_counter}, state={state_dbg}, yolomedia_running={yolomedia_running}")
                
                # 统一解码（添加更严格的异常处理）
                try:
                    arr = np.frombuffer(data, dtype=np.uint8)
                    bgr = cv2.imdecode(arr, cv2.IMREAD_COLOR)
                    # 验证解码结果
                    if bgr is None or bgr.size == 0:
                        if frame_counter % 30 == 0:
                            print(f"[JPEG] 解码失败：数据长度={len(data)}")
                        bgr = None
                except Exception as e:
                    if frame_counter % 30 == 0:
                        print(f"[JPEG] 解码异常: {e}")
                    bgr = None

                if bgr is not None:
                    update_latest_camera_frame(bgr, len(data), frame_counter)

                # 【托管】优先交给统领状态机（寻物未占用画面时）
                # 【修改】找物品模式时不执行导航处理，让yolomedia接管画面
                if orchestrator and not yolomedia_running and bgr is not None:
                    current_state = orchestrator.get_state()
                    
                    # 【新增】找物品模式：不处理画面，等待yolomedia发送处理后的帧
                    if current_state == "ITEM_SEARCH":
                        # 找物品模式下，如果yolomedia还没开始发送帧，先显示原始画面
                        if not yolomedia_sending_frames and camera_viewers:
                            jpeg_data = encode_viewer_jpeg(bgr, 80)
                            if jpeg_data:
                                dead = []
                                for viewer_ws in list(camera_viewers):
                                    try:
                                        await viewer_ws.send_bytes(jpeg_data)
                                    except Exception:
                                        dead.append(viewer_ws)
                                for d in dead:
                                    camera_viewers.discard(d)
                        continue  # 跳过后续的导航处理
                    
                    out_img = bgr
                    try:
                        res = orchestrator.process_frame(bgr)

                        latest_alert = None
                        try:
                            extras = res.extras or {}
                            latest_alert = extras.get("latest_alert") or extras.get("multimodal_alert")
                        except Exception:
                            latest_alert = None
                        if latest_alert:
                            await ui_broadcast_multimodal_alert(latest_alert)

                        # 语音引导（内部已节流）
                        # 注：omni对话时已切换到CHAT模式，不会生成导航语音
                        if res.guidance_text:
                            try:
                                # 先播放语音，再广播到UI
                                play_voice_text(res.guidance_text)
                                await ui_broadcast_final(f"[导航] {res.guidance_text}")
                            except Exception:
                                pass

                            # 输出图像
                            out_img = res.annotated_image if res.annotated_image is not None else bgr
                    except Exception as e:
                        if frame_counter % 100 == 0:
                            print(f"[NAV MASTER] 处理帧时出错: {e}")

                    # 广播图像
                    if camera_viewers and out_img is not None:
                        jpeg_data = encode_viewer_jpeg(out_img, 80)
                        if jpeg_data:
                            dead = []
                            for viewer_ws in list(camera_viewers):
                                try:
                                    await viewer_ws.send_bytes(jpeg_data)
                                except Exception:
                                    dead.append(viewer_ws)
                            for d in dead:
                                camera_viewers.discard(d)
                    # 已托管，进入下一帧
                    continue

                # 【回退】寻物占用或者未解码成功，按原始画面回传
                if not yolomedia_sending_frames and camera_viewers:
                    try:
                        if bgr is None:
                            arr = np.frombuffer(data, dtype=np.uint8)
                            bgr = cv2.imdecode(arr, cv2.IMREAD_COLOR)
                        if bgr is not None:
                            jpeg_data = encode_viewer_jpeg(bgr, 80)
                            if jpeg_data:
                                dead = []
                                for viewer_ws in list(camera_viewers):
                                    try:
                                        await viewer_ws.send_bytes(jpeg_data)
                                    except Exception:
                                        dead.append(viewer_ws)
                                for ws in dead:
                                    camera_viewers.discard(ws)
                    except Exception as e:
                        print(f"[CAMERA] Broadcast error: {e}")

            elif "type" in msg and msg["type"] in ("websocket.close", "websocket.disconnect"):
                break
    except WebSocketDisconnect:
        pass
    except Exception as e:
        print(f"[CAMERA ERROR] {e}")
    finally:
        try:
            if WebSocketState is None or ws.client_state == WebSocketState.CONNECTED:
                await ws.close(code=1000)
        except Exception:
            pass
        mobile_camera_ws = None
        # Close all viewers when camera disconnects
        for viewer_ws in list(camera_viewers):
            try: await viewer_ws.close(code=1000, reason="camera offline")
            except Exception: pass
        camera_viewers.clear()
        if safety_monitor_task and not safety_monitor_task.done():
            safety_monitor_task.cancel()
            try: await safety_monitor_task
            except (asyncio.CancelledError, Exception): pass
        safety_monitor_task = None
        print("[CAMERA] Mobile client disconnected, viewers cleaned")

        # 【新增】清理导航状态
        if blind_path_navigator:
            blind_path_navigator.reset()
        if cross_street_navigator:
            cross_street_navigator.reset()
        if orchestrator:
            orchestrator.reset()
            print("[NAV MASTER] 统领器已重置")

# ---------- WebSocket：浏览器订阅相机帧 ----------
@app.websocket("/ws/viewer")
async def ws_viewer(ws: WebSocket):
    await ws.accept()
    camera_viewers.add(ws)
    print(f"[VIEWER] Browser connected. Total viewers: {len(camera_viewers)}", flush=True)
    try:
        while True:
            await asyncio.sleep(60)
    except WebSocketDisconnect:
        print("[VIEWER] Browser disconnected", flush=True)
    finally:
        try:
            camera_viewers.remove(ws)
        except Exception:
            pass
        print(f"[VIEWER] Removed. Total viewers: {len(camera_viewers)}", flush=True)

# ---------- WebSocket：社交状态推送（好友状态 / 紧急通知） ----------
@app.websocket("/ws/social")
async def ws_social(ws: WebSocket):
    await handle_status_websocket(ws)

# === 新增：注册给 bridge_io 的发送回调（把 JPEG 广播给 /ws/viewer） ===
@app.on_event("startup")
async def on_startup_register_bridge_sender():
    # 保存主线程的事件循环
    main_loop = asyncio.get_event_loop()
    
    def _sender(jpeg_bytes: bytes):
        # 注意：这个函数可能在非协程线程里被调用，需要切回主事件循环
        try:
            # 检查事件循环状态，避免在关闭时发送
            if main_loop.is_closed():
                return
            
            # 标记YOLO已经开始发送处理后的帧
            global yolomedia_sending_frames
            if not yolomedia_sending_frames:
                yolomedia_sending_frames = True
                print("[YOLOMEDIA] 开始发送处理后的帧，切换到YOLO画面", flush=True)
            
            async def _broadcast():
                if not camera_viewers:
                    return
                dead = []
                for ws in list(camera_viewers):
                    try:
                        await ws.send_bytes(jpeg_bytes)
                    except Exception as e:
                        dead.append(ws)
                for ws in dead:
                    try:
                        camera_viewers.remove(ws)
                    except Exception:
                        pass
                        pass
            
            # 使用保存的主线程事件循环
            future = asyncio.run_coroutine_threadsafe(_broadcast(), main_loop)
            # 不等待结果，避免阻塞生产线程
        except Exception as e:
            # 只在非预期错误时打印日志
            if "Event loop is closed" not in str(e):
                print(f"[DEBUG] _sender error: {e}", flush=True)

    bridge_io.set_sender(_sender)

@app.on_event("startup")
async def on_startup_init_audio():
    """启动时初始化音频系统"""
    # 在后台线程中初始化，避免阻塞启动
    def _init():
        try:
            initialize_audio_system()
        except Exception as e:
            print(f"[AUDIO] 初始化失败: {e}")
    
    threading.Thread(target=_init, daemon=True).start()

@app.on_event("startup")
async def on_startup_init_social_db():
    """启动时初始化社交数据库"""
    try:
        init_db()
        print("[SOCIAL] 社交数据库初始化完成", flush=True)
    except Exception as e:
        print(f"[SOCIAL] 数据库初始化失败: {e}", flush=True)

@app.on_event("shutdown")
async def on_shutdown():
    """应用关闭时的清理工作"""
    print("[SHUTDOWN] 开始清理资源...")
    
    # 停止YOLO媒体处理
    stop_yolomedia()
    
    # 停止音频和AI任务
    await hard_reset_audio("shutdown")
    
    print("[SHUTDOWN] 资源清理完成")


# --- 导出接口（可选） ---
def get_last_frames():
    return last_frames

def get_camera_ws():
    return mobile_camera_ws

if __name__ == "__main__":
    uvicorn.run(
        app, host="0.0.0.0", port=8081,
        log_level="warning", access_log=False,
        loop="asyncio", workers=1, reload=False
    )

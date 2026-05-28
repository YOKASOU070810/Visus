# audio_stream.py
# -*- coding: utf-8 -*-
import asyncio
import time
from dataclasses import dataclass
from typing import Optional, Set, List, Tuple, Any, Dict
from fastapi import Request
from fastapi.responses import StreamingResponse

# ===== 下行 WAV 流基础参数 =====
STREAM_SR = 8000  # 8kHz采样率，适配移动设备
STREAM_CH = 1
STREAM_SW = 2
BYTES_PER_20MS_16K = STREAM_SR * STREAM_SW * 20 // 1000  # 320B (8kHz)
STREAM_SEND_CHUNK_BYTES = BYTES_PER_20MS_16K * 5         # 100ms chunks reduce HTTP jitter

# ===== AI 播放任务总闸 =====
current_ai_task: Optional[asyncio.Task] = None
_reply_perf_start: Optional[float] = None
_first_stream_write_logged = False

def mark_audio_reply_start(start_ts: Optional[float] = None):
    global _reply_perf_start, _first_stream_write_logged
    _reply_perf_start = start_ts if start_ts is not None else time.perf_counter()
    _first_stream_write_logged = False

async def cancel_current_ai():
    """取消当前大模型语音任务，并等待其退出。"""
    global current_ai_task
    task = current_ai_task
    current_ai_task = None
    if task and not task.done():
        task.cancel()
        try:
            await task
        except asyncio.CancelledError:
            pass
        except Exception:
            pass

def is_playing_now() -> bool:
    t = current_ai_task
    return (t is not None) and (not t.done())

# ===== /stream.wav 连接管理 =====
@dataclass(eq=False)
class StreamClient:
    q: asyncio.Queue
    abort_event: asyncio.Event
    last_active: float = 0.0

stream_clients: "Set[StreamClient]" = set()
STREAM_QUEUE_MAX = 512  # allow smooth client-side buffering; reset clears stale audio

def _wav_header_unknown_size(sr=16000, ch=1, sw=2) -> bytes:
    import struct
    byte_rate = sr * ch * sw
    block_align = ch * sw
    data_size = 0x7FFFFFF0
    riff_size = 36 + data_size
    return struct.pack(
        "<4sI4s4sIHHIIHH4sI",
        b"RIFF", riff_size, b"WAVE",
        b"fmt ", 16,
        1, ch, sr, byte_rate, block_align, sw * 8,
        b"data", data_size
    )

async def hard_reset_audio(reason: str = ""):
    """
    **一键清场**：丢弃所有播放器连接（abort_event置位）+ 取消当前AI任务。
    这样旧的音频不会再有任何去处，也没有任何任务继续产出。
    """
    # 1) 断开所有正在播放的 HTTP 连接
    for sc in list(stream_clients):
        try:
            sc.abort_event.set()
        except Exception:
            pass
    stream_clients.clear()

    # 2) 取消当前AI任务
    await cancel_current_ai()

    # 3) 日志
    if reason:
        print(f"[HARD-RESET] {reason}")

async def soft_reset_audio(reason: str = ""):
    """
    Reset the current AI audio generation without dropping /stream.wav clients.
    This is used before starting a normal AI reply: old queued audio is discarded,
    the previous AI task is cancelled, and connected players stay attached for
    the next PCM chunks.
    """
    await cancel_current_ai()

    silence = b"\x00" * BYTES_PER_20MS_16K
    for sc in list(stream_clients):
        if sc.abort_event.is_set():
            continue
        try:
            while True:
                sc.q.get_nowait()
        except asyncio.QueueEmpty:
            pass
        except Exception:
            pass
        try:
            sc.q.put_nowait(silence)
        except Exception:
            pass

    if reason:
        print(f"[SOFT-RESET] {reason}")

async def broadcast_pcm16_realtime(pcm16: bytes):
    """Queue PCM for connected /stream.wav clients.

    Playback pacing belongs to the client AudioTrack/browser decoder. Sleeping
    here on every 20ms frame adds event-loop and network jitter that is audible
    as stutter on Android.
    """
    global _first_stream_write_logged
    # 【新增】录制音频（在分发之前整体录制，避免分片）
    try:
        import sync_recorder
        sync_recorder.record_audio(pcm16, text="[Omni对话]")
    except Exception:
        pass  # 静默失败，不影响播放
    
    off = 0
    while off < len(pcm16):
        take = min(STREAM_SEND_CHUNK_BYTES, len(pcm16) - off)
        piece = pcm16[off:off + take]

        import time as _time2
        dead: List[StreamClient] = []
        for sc in list(stream_clients):
            if sc.abort_event.is_set():
                dead.append(sc)
                continue
            try:
                sc.last_active = _time2.time()
                if sc.q.full():
                    try: sc.q.get_nowait()
                    except Exception: pass
                sc.q.put_nowait(piece)
                if not _first_stream_write_logged and piece:
                    _first_stream_write_logged = True
                    if _reply_perf_start is not None:
                        print(f"[PERF] ai_start_to_first_stream_write={(time.perf_counter() - _reply_perf_start) * 1000:.1f} ms", flush=True)
            except Exception:
                dead.append(sc)
        for sc in dead:
            try: stream_clients.discard(sc)
            except Exception: pass

        off += take

# ===== FastAPI 路由注册器 =====
def register_stream_route(app):
    @app.get("/stream.wav")
    async def stream_wav(_: Request):
        # Keep idle stream clients around long enough for the next reply.
        # Android keeps /stream.wav open while waiting; a short timeout can
        # close the stream just before a new answer starts and lose audio.
        import time as _time
        now = _time.time()
        for sc in list(stream_clients):
            if getattr(sc, 'last_active', 0) < now - 120:
                try: sc.abort_event.set()
                except Exception: pass
                stream_clients.discard(sc)

        q: asyncio.Queue[bytes | None] = asyncio.Queue(maxsize=STREAM_QUEUE_MAX)
        abort_event = asyncio.Event()
        sc = StreamClient(q=q, abort_event=abort_event)
        sc.last_active = now
        stream_clients.add(sc)

        async def gen():
            yield _wav_header_unknown_size(STREAM_SR, STREAM_CH, STREAM_SW)
            try:
                while True:
                    if abort_event.is_set():
                        break
                    try:
                        chunk = await asyncio.wait_for(q.get(), timeout=0.5)
                    except asyncio.TimeoutError:
                        continue
                    if abort_event.is_set():
                        break
                    if chunk is None:
                        break
                    if chunk:
                        yield chunk
            finally:
                stream_clients.discard(sc)
        return StreamingResponse(gen(), media_type="audio/wav")

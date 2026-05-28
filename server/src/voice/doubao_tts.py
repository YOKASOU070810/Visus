# doubao_tts.py
# -*- coding: utf-8 -*-
import base64
import io
import json
import os
import uuid
import wave
from typing import Optional

import requests

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

TTS_API_URL = os.getenv("VOLCENGINE_TTS_API_URL", "https://openspeech.bytedance.com/api/v1/tts")
TTS_APP_ID = os.getenv("VOLCENGINE_TTS_APP_ID", "")
TTS_ACCESS_TOKEN = os.getenv("VOLCENGINE_TTS_ACCESS_TOKEN", "")
TTS_CLUSTER = os.getenv("VOLCENGINE_TTS_CLUSTER", "volcano_tts")
TTS_VOICE_TYPE = os.getenv("VOLCENGINE_TTS_VOICE_TYPE", "BV700_V2_streaming")
TTS_ENCODING = os.getenv("VOLCENGINE_TTS_ENCODING", "wav")
TTS_SPEED_RATIO = float(os.getenv("VOLCENGINE_TTS_SPEED_RATIO", "1.0"))
TTS_VOLUME_RATIO = float(os.getenv("VOLCENGINE_TTS_VOLUME_RATIO", "1.0"))
TTS_PITCH_RATIO = float(os.getenv("VOLCENGINE_TTS_PITCH_RATIO", "1.0"))
TTS_TIMEOUT_SEC = float(os.getenv("VOLCENGINE_TTS_TIMEOUT_SEC", "12"))

def is_configured() -> bool:
    return bool(TTS_APP_ID and TTS_ACCESS_TOKEN and TTS_CLUSTER and TTS_VOICE_TYPE)

def _wav_to_pcm8k(audio_bytes: bytes) -> bytes:
    with wave.open(io.BytesIO(audio_bytes), "rb") as wav:
        channels = wav.getnchannels()
        sampwidth = wav.getsampwidth()
        framerate = wav.getframerate()
        frames = wav.readframes(wav.getnframes())

    if sampwidth != 2:
        return b""
    if channels == 2:
        frames = audioop.tomono(frames, sampwidth, 1, 0)
    if framerate != 8000:
        frames, _ = audioop.ratecv(frames, sampwidth, 1, framerate, 8000, None)
    return audioop.mul(frames, 2, 0.75)

def synthesize_to_pcm8k(text: str) -> Optional[bytes]:
    clean_text = (text or "").strip()
    if not clean_text:
        return None
    if not is_configured():
        print("[DOUBAO TTS] not configured: set VOLCENGINE_TTS_APP_ID and VOLCENGINE_TTS_ACCESS_TOKEN", flush=True)
        return None

    payload = {
        "app": {
            "appid": TTS_APP_ID,
            "token": "access_token",
            "cluster": TTS_CLUSTER,
        },
        "user": {
            "uid": os.getenv("VOLCENGINE_TTS_UID", "visus"),
        },
        "audio": {
            "voice_type": TTS_VOICE_TYPE,
            "encoding": TTS_ENCODING,
            "speed_ratio": TTS_SPEED_RATIO,
            "volume_ratio": TTS_VOLUME_RATIO,
            "pitch_ratio": TTS_PITCH_RATIO,
        },
        "request": {
            "reqid": uuid.uuid4().hex,
            "text": clean_text,
            "text_type": "plain",
            "operation": "query",
        },
    }

    headers = {
        "Authorization": f"Bearer;{TTS_ACCESS_TOKEN}",
        "Content-Type": "application/json",
    }

    try:
        resp = requests.post(TTS_API_URL, headers=headers, data=json.dumps(payload).encode("utf-8"), timeout=TTS_TIMEOUT_SEC)
        resp.raise_for_status()
        data = resp.json()
    except Exception as e:
        print(f"[DOUBAO TTS] request failed: {repr(e)}", flush=True)
        return None

    if data.get("code") not in (None, 0, 3000):
        print(f"[DOUBAO TTS] synthesis failed: {data}", flush=True)
        return None

    audio_b64 = data.get("data")
    if not audio_b64:
        print(f"[DOUBAO TTS] no audio data in response: {data}", flush=True)
        return None

    try:
        audio_bytes = base64.b64decode(audio_b64)
        if TTS_ENCODING.lower() == "wav" or audio_bytes[:4] == b"RIFF":
            return _wav_to_pcm8k(audio_bytes)
        print(f"[DOUBAO TTS] unsupported encoding for direct playback: {TTS_ENCODING}", flush=True)
        return None
    except Exception as e:
        print(f"[DOUBAO TTS] decode failed: {repr(e)}", flush=True)
        return None

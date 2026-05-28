# omni_client.py
# -*- coding: utf-8 -*-
import os, base64
from typing import AsyncGenerator, Dict, Any, List, Optional, Tuple

from openai import OpenAI
from utils.system_prompt import load_builtin_system_prompt

# ===== OpenAI 兼容（达摩院 DashScope 兼容模式）=====
API_KEY = os.getenv("DASHSCOPE_API_KEY", "")
if not API_KEY:
    raise RuntimeError("未设置 DASHSCOPE_API_KEY")

QWEN_MODEL = os.getenv("QWEN_OMNI_MODEL", "qwen-omni-turbo")
QWEN_TEXT_MODEL = os.getenv("QWEN_TEXT_MODEL", "qwen-plus")
BUILTIN_SYSTEM_PROMPT = load_builtin_system_prompt()
VOICE_REPLY_RULES = (
    "\n\n补充语音播报规则："
    "除危险预警或复杂导航外，通常用 1 到 3 句回答；"
    "先回应用户当前问题，再结合画面给出必要的出行辅助；"
    "如果用户只是打招呼，要友好回应，并提示可以帮忙看路、找物品、读文字或描述周围。"
)

# 兼容模式
oai_client = OpenAI(
    api_key=API_KEY,
    base_url="https://dashscope.aliyuncs.com/compatible-mode/v1",
)

class OmniStreamPiece:
    """对外的统一增量数据：text/audio 二选一或同时。"""
    def __init__(self, text_delta: Optional[str] = None, audio_b64: Optional[str] = None):
        self.text_delta = text_delta
        self.audio_b64  = audio_b64

def _normalize_delta_content(content: Any) -> str:
    if isinstance(content, str):
        return content
    if isinstance(content, list):
        parts: List[str] = []
        for item in content:
            if isinstance(item, str):
                parts.append(item)
            elif isinstance(item, dict):
                text = item.get("text")
                if isinstance(text, str):
                    parts.append(text)
        return "".join(parts)
    return ""

def _create_chat_completion(request_kwargs: Dict[str, Any]):
    """
    openai==1.3.5 does not expose newer multimodal kwargs such as
    modalities/audio in the Python signature. DashScope compatible mode still
    accepts them in the raw request body, so pass them through extra_body.
    """
    return oai_client.chat.completions.create(**request_kwargs)

def _text_only_messages(system_prompt: str, content_list: List[Dict[str, Any]]) -> List[Dict[str, str]]:
    text_parts: List[str] = []
    for item in content_list:
        if isinstance(item, dict) and item.get("type") == "text":
            text = item.get("text")
            if isinstance(text, str) and text.strip():
                text_parts.append(text.strip())
    user_text = "\n".join(text_parts).strip() or "你好"
    return [
        {"role": "system", "content": system_prompt},
        {"role": "user", "content": user_text},
    ]

async def stream_chat(
    content_list: List[Dict[str, Any]],
    voice: str = "Cherry",
    audio_format: str = "wav",
    include_audio: bool = True,
) -> AsyncGenerator[OmniStreamPiece, None]:
    """
    发起一轮 Omni-Turbo ChatCompletions 流式对话：
    - content_list: OpenAI chat 的 content，多模态（image_url/text）
    - 以 stream=True 返回
    - 增量产出：OmniStreamPiece(text_delta=?, audio_b64=?)
    """
    system_prompt = BUILTIN_SYSTEM_PROMPT + VOICE_REPLY_RULES
    request_kwargs: Dict[str, Any] = {
        "model": QWEN_MODEL,
        "messages": [
            {"role": "system", "content": system_prompt},
            {"role": "user", "content": content_list},
        ],
        "stream": True,
    }
    if include_audio:
        request_kwargs["extra_body"] = {
            "modalities": ["text", "audio"],
            "audio": {"voice": voice, "format": audio_format},
        }

    try:
        completion = _create_chat_completion(request_kwargs)
    except TypeError as e:
        print(f"[OMNI WARN] audio request unsupported by SDK, falling back to text: {e}", flush=True)
        request_kwargs.pop("extra_body", None)
        request_kwargs["model"] = QWEN_TEXT_MODEL
        request_kwargs["messages"] = _text_only_messages(system_prompt, content_list)
        completion = _create_chat_completion(request_kwargs)
    except Exception as e:
        if include_audio:
            print(f"[OMNI WARN] audio request failed, falling back to text: {repr(e)}", flush=True)
            request_kwargs.pop("extra_body", None)
            request_kwargs["model"] = QWEN_TEXT_MODEL
            request_kwargs["messages"] = _text_only_messages(system_prompt, content_list)
            completion = _create_chat_completion(request_kwargs)
        else:
            raise

    # 注意：OpenAI SDK 的流是同步迭代器；在 async 场景下逐项 yield
    try:
        for chunk in completion:
            text_delta: Optional[str] = None
            audio_b64: Optional[str] = None

            if getattr(chunk, "choices", None):
                c0 = chunk.choices[0]
                delta = getattr(c0, "delta", None)
                # 文本增量
                if delta and getattr(delta, "content", None):
                    piece = _normalize_delta_content(delta.content)
                    if piece:
                        text_delta = piece
                # 音频分片
                if delta and getattr(delta, "audio", None):
                    aud = delta.audio
                    audio_b64 = aud.get("data") if isinstance(aud, dict) else getattr(aud, "data", None)
                if audio_b64 is None:
                    msg = getattr(c0, "message", None)
                    if msg and getattr(msg, "audio", None):
                        ma = msg.audio
                        audio_b64 = ma.get("data") if isinstance(ma, dict) else getattr(ma, "data", None)

            if (text_delta is not None) or (audio_b64 is not None):
                yield OmniStreamPiece(text_delta=text_delta, audio_b64=audio_b64)
    except Exception as e:
        if not include_audio:
            raise
        print(f"[OMNI WARN] audio stream failed, retrying text only: {repr(e)}", flush=True)
        fallback_kwargs = dict(request_kwargs)
        fallback_kwargs.pop("extra_body", None)
        fallback_kwargs["model"] = QWEN_TEXT_MODEL
        fallback_kwargs["messages"] = _text_only_messages(system_prompt, content_list)
        fallback = _create_chat_completion(fallback_kwargs)
        text_delta: Optional[str] = None
        for chunk in fallback:
            if getattr(chunk, "choices", None):
                c0 = chunk.choices[0]
                delta = getattr(c0, "delta", None)
                if delta and getattr(delta, "content", None):
                    piece = _normalize_delta_content(delta.content)
                    if piece:
                        text_delta = piece
            if text_delta:
                yield OmniStreamPiece(text_delta=text_delta)
                text_delta = None

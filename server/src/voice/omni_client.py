# doubao_client.py-compatible module name
# -*- coding: utf-8 -*-
import os
import asyncio
from typing import AsyncGenerator, Dict, Any, List, Optional

from openai import OpenAI
from utils.system_prompt import load_builtin_system_prompt

# Volcengine Ark uses an OpenAI-compatible Chat Completions endpoint.
ARK_API_KEY = os.getenv("ARK_API_KEY", "")
ARK_BASE_URL = os.getenv("ARK_BASE_URL", "https://ark.cn-beijing.volces.com/api/v3")
_HAS_ARK = bool(ARK_API_KEY)

# In Ark, model should usually be your endpoint ID, for example "ep-xxxxxxxx".
DOUBAO_MODEL = os.getenv("DOUBAO_MODEL", os.getenv("ARK_MODEL", ""))
_HAS_ARK_MODEL = bool(DOUBAO_MODEL)

if not _HAS_ARK:
    print("[WARN] ARK_API_KEY not set — LLM chat streaming disabled", flush=True)
elif not _HAS_ARK_MODEL:
    print("[WARN] DOUBAO_MODEL/ARK_MODEL not set — LLM chat streaming disabled", flush=True)

BUILTIN_SYSTEM_PROMPT = load_builtin_system_prompt()
VOICE_REPLY_RULES = (
    "\n\n补充语音播报规则："
    "除危险预警或复杂导航外，最多用 1 到 2 句回答，适合直接语音播报；"
    "涉及画面、障碍物、道路、红绿灯、物品位置的问题，如果提供了当前帧，就优先根据当前帧回答；"
    "如果没有当前帧，也要直接给出有帮助的简短回答；"
    "如果当前帧能判断，就直接回答物体、方位和大致距离，不要先要求用户调整摄像头；"
    "不要主动提示调整摄像头，除非用户明确询问画面质量；"
    "同一轮回答只说一次结论，不要输出过程状态。"
)

ark_client = None
if _HAS_ARK:
    ark_client = OpenAI(
        api_key=ARK_API_KEY,
        base_url=ARK_BASE_URL,
    )


_SENTINEL = object()


def _next_stream_item(iterator):
    try:
        return next(iterator)
    except StopIteration:
        return _SENTINEL

class OmniStreamPiece:
    """统一增量数据结构；豆包方舟 Chat Completions 当前只返回文本增量。"""
    def __init__(self, text_delta: Optional[str] = None, audio_b64: Optional[str] = None):
        self.text_delta = text_delta
        self.audio_b64 = audio_b64

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
    使用火山方舟豆包模型发起流式对话。

    继续保留 include_audio 参数是为了兼容 app_main.py 的调用签名；豆包方舟
    Chat Completions 不通过该接口直接返回音频，因此这里仅产出文本。
    """
    system_prompt = BUILTIN_SYSTEM_PROMPT + VOICE_REPLY_RULES
    if ark_client is None or not _HAS_ARK:
        yield OmniStreamPiece(text_delta="（AI 语音助手未配置，请设置 ARK_API_KEY）")
        return

    messages: List[Dict[str, Any]] = [
        {"role": "system", "content": system_prompt},
        {"role": "user", "content": content_list},
    ]

    request_kwargs: Dict[str, Any] = {
        "model": DOUBAO_MODEL,
        "messages": messages,
        "stream": True,
        "max_tokens": int(os.getenv("ARK_MAX_TOKENS", "80")),
    }

    try:
        completion = await asyncio.to_thread(ark_client.chat.completions.create, **request_kwargs)
    except Exception as e:
        print(f"[DOUBAO WARN] multimodal request failed, retrying text only: {repr(e)}", flush=True)
        completion = await asyncio.to_thread(
            ark_client.chat.completions.create,
            model=DOUBAO_MODEL,
            messages=_text_only_messages(system_prompt, content_list),
            stream=True,
            max_tokens=int(os.getenv("ARK_MAX_TOKENS", "80")),
        )

    iterator = iter(completion)
    while True:
        chunk = await asyncio.to_thread(_next_stream_item, iterator)
        if chunk is _SENTINEL:
            break
        text_delta: Optional[str] = None
        if getattr(chunk, "choices", None):
            c0 = chunk.choices[0]
            delta = getattr(c0, "delta", None)
            if delta and getattr(delta, "content", None):
                piece = _normalize_delta_content(delta.content)
                if piece:
                    text_delta = piece

        if text_delta is not None:
            yield OmniStreamPiece(text_delta=text_delta)

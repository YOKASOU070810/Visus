# doubao_client.py-compatible module name
# -*- coding: utf-8 -*-
import os
from typing import AsyncGenerator, Dict, Any, List, Optional

from openai import OpenAI
from utils.system_prompt import load_builtin_system_prompt

# Volcengine Ark uses an OpenAI-compatible Chat Completions endpoint.
ARK_API_KEY = os.getenv("ARK_API_KEY", "")
ARK_BASE_URL = os.getenv("ARK_BASE_URL", "https://ark.cn-beijing.volces.com/api/v3")
if not ARK_API_KEY:
    raise RuntimeError("未设置 ARK_API_KEY")

# In Ark, model should usually be your endpoint ID, for example "ep-xxxxxxxx".
DOUBAO_MODEL = os.getenv("DOUBAO_MODEL", os.getenv("ARK_MODEL", ""))
if not DOUBAO_MODEL:
    raise RuntimeError("未设置 DOUBAO_MODEL 或 ARK_MODEL")

BUILTIN_SYSTEM_PROMPT = load_builtin_system_prompt()
VOICE_REPLY_RULES = (
    "\n\n补充语音播报规则："
    "除危险预警或复杂导航外，通常用 1 到 3 句回答；"
    "先回应用户当前问题，再结合画面给出必要的出行辅助；"
    "如果用户只是打招呼，要友好回应，并提示可以帮忙看路、找物品、读文字或描述周围。"
)

ark_client = OpenAI(
    api_key=ARK_API_KEY,
    base_url=ARK_BASE_URL,
)

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
    messages: List[Dict[str, Any]] = [
        {"role": "system", "content": system_prompt},
        {"role": "user", "content": content_list},
    ]

    request_kwargs: Dict[str, Any] = {
        "model": DOUBAO_MODEL,
        "messages": messages,
        "stream": True,
    }

    try:
        completion = ark_client.chat.completions.create(**request_kwargs)
    except Exception as e:
        print(f"[DOUBAO WARN] multimodal request failed, retrying text only: {repr(e)}", flush=True)
        completion = ark_client.chat.completions.create(
            model=DOUBAO_MODEL,
            messages=_text_only_messages(system_prompt, content_list),
            stream=True,
        )

    for chunk in completion:
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

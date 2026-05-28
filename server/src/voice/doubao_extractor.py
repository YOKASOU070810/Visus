# doubao_extractor.py
# -*- coding: utf-8 -*-
from typing import Tuple
import os

try:
    from openai import OpenAI
except (ModuleNotFoundError, ImportError):
    OpenAI = None

LOCAL_CN2EN = {
    "红牛": "Red_Bull",
    "ad钙奶": "AD_milk",
    "ad 钙奶": "AD_milk",
    "ad": "AD_milk",
    "钙奶": "AD_milk",
    "矿泉水": "bottle",
    "水瓶": "bottle",
    "可乐": "coke",
    "雪碧": "sprite",
}

PROMPT_SYS = (
    "You are a label normalizer. Convert the given Chinese object "
    "description into a short, lowercase English YOLO/vision class name "
    "(1 to 3 words). If multiple are given, return the single most likely one. "
    "Output ONLY the label, no punctuation."
)

def _make_client():
    if OpenAI is None:
        raise RuntimeError("openai package is not installed")
    api_key = os.getenv("ARK_API_KEY", "")
    model = os.getenv("DOUBAO_MODEL", os.getenv("ARK_MODEL", ""))
    if not api_key:
        raise RuntimeError("ARK_API_KEY is empty")
    if not model:
        raise RuntimeError("DOUBAO_MODEL or ARK_MODEL is empty")
    base_url = os.getenv("ARK_BASE_URL", "https://ark.cn-beijing.volces.com/api/v3")
    return OpenAI(api_key=api_key, base_url=base_url), model

def extract_english_label(query_cn: str) -> Tuple[str, str]:
    """
    返回 (label_en, source)；source ∈ {'local', 'doubao', 'fallback'}
    """
    q = (query_cn or "").strip().lower()
    if q in LOCAL_CN2EN:
        return LOCAL_CN2EN[q], "local"

    for k, v in LOCAL_CN2EN.items():
        if k in q:
            return v, "local"

    try:
        client, model = _make_client()
        rsp = client.chat.completions.create(
            model=model,
            messages=[
                {"role": "system", "content": PROMPT_SYS},
                {"role": "user", "content": query_cn.strip()},
            ],
            stream=False,
        )
        label = (rsp.choices[0].message.content or "").strip()
        label = label.replace(".", "").replace(",", "").replace("  ", " ").strip()
        return (label or "bottle"), "doubao"
    except Exception as e:
        print(f"[DOUBAO EXTRACTOR WARN] {repr(e)}", flush=True)
        return "bottle", "fallback"

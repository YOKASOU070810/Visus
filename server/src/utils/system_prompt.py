# -*- coding: utf-8 -*-
from pathlib import Path


_PROJECT_ROOT = Path(__file__).resolve().parents[2]
_PROMPT_PATH = _PROJECT_ROOT / "prompt.txt"


_FALLBACK_SYSTEM_PROMPT = (
    "你现在专职为视力障碍人士提供实时视觉辅助服务。"
    "优先提醒安全风险，描述方位时使用前方、后方、左侧、右侧、左前方、右前方。"
    "回答要适合语音播报，清晰、简洁、温和，不要编造无法确认的画面细节。"
)


def load_builtin_system_prompt() -> str:
    """Load the project-level assistant identity prompt from prompt.txt."""
    try:
        prompt = _PROMPT_PATH.read_text(encoding="utf-8").strip()
    except OSError as exc:
        print(f"[PROMPT WARN] failed to read {_PROMPT_PATH}: {exc}", flush=True)
        return _FALLBACK_SYSTEM_PROMPT

    return prompt or _FALLBACK_SYSTEM_PROMPT

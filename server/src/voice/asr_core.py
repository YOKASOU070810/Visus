# asr_core.py
# -*- coding: utf-8 -*-
import os, json, asyncio, time
from typing import Any, Dict, List, Optional, Callable, Tuple

ASR_DEBUG_RAW = os.getenv("ASR_DEBUG_RAW", "0") == "1"
ASR_AUTO_REPLY_STABLE_SEC = float(os.getenv("ASR_AUTO_REPLY_STABLE_SEC", "1.4"))

def _shorten(s: str, limit: int = 200) -> str:
    if not s:
        return ""
    return s if len(s) <= limit else (s[:limit] + "…")

def _safe_to_dict(x: Any) -> Dict[str, Any]:
    if isinstance(x, dict): return x
    for attr in ("to_dict", "model_dump", "__dict__"):
        try:
            v = getattr(x, attr, None)
        except Exception:
            v = None
        if callable(v):
            try:
                d = v()
                if isinstance(d, dict): return d
            except Exception:
                pass
        elif isinstance(v, dict):
            return v
    try:
        s = str(x)
        if s and s.lstrip().startswith("{") and s.rstrip().endswith("}"):
            return json.loads(s)
    except Exception:
        pass
    return {"_raw": str(x)}

def _extract_sentence(event_obj: Any) -> Tuple[Optional[str], Optional[bool]]:
    d = _safe_to_dict(event_obj)
    cands: List[Dict[str, Any]] = [d]
    for k in ("output", "data", "result"):
        v = d.get(k)
        if isinstance(v, dict):
            cands.append(v)
    for obj in cands:
        sent = obj.get("sentence")
        if isinstance(sent, dict):
            text = sent.get("text")
            is_end = sent.get("sentence_end")
            if is_end is not None:
                is_end = bool(is_end)
            return text, is_end
    for obj in cands:
        if "text" in obj and isinstance(obj.get("text"), str):
            return obj.get("text"), None
    return None, None

# ====== 仅热词触发的“全清零复位”配置 ======
INTERRUPT_KEYWORDS = set(
    os.getenv("INTERRUPT_KEYWORDS", "停下,别说了,停止").split(",")
)

def _normalize_cn(s: str) -> str:
    try:
        import unicodedata
        s = "".join(" " if unicodedata.category(ch) == "Zs" else ch for ch in s)
        s = s.strip().lower()
    except Exception:
        s = (s or "").strip().lower()
    return s

# ============ ASR 全局总闸 ============
_current_recognition: Optional[object] = None
_rec_lock = asyncio.Lock()

async def set_current_recognition(r):
    global _current_recognition
    async with _rec_lock:
        _current_recognition = r

async def stop_current_recognition():
    global _current_recognition
    async with _rec_lock:
        r = _current_recognition
        _current_recognition = None
    if r:
        try:
            r.stop()  # DashScope SDK 的实时识别停止
        except Exception:
            pass

# ============ ASR 回调 ============
class ASRCallback:
    """
    设计目标：
    1) “停下 / 别说了 …”等热词一出现 → 立刻全清零复位（恢复到刚启动后的状态）。
    2) 除此之外【不接受打断】；AI 正在播报时，用户说话只做展示，不触发新一轮。
    3) 不再用 partial 叠加字符串；partial 只用于 UI 临时展示；只有 final sentence 用于驱动 AI。
    """

    def __init__(
        self,
        on_sdk_error: Callable[[str], None],
        post: Callable[[asyncio.Future], None],
        ui_broadcast_partial,
        ui_broadcast_final,
        is_playing_now_fn: Callable[[], bool],
        start_ai_with_text_fn,               # async (text)
        full_system_reset_fn,                 # async (reason)
        interrupt_lock: asyncio.Lock,
    ):
        self._on_sdk_error = on_sdk_error
        self._post = post
        self._last_partial_for_ui: str = ""   # 只用于 UI 展示
        self._last_final_text: str = ""       # 以句末 final 为准
        self._last_ai_text: str = ""          # 避免同一段 partial/final 重复送 LLM
        self._hot_interrupted: bool = False   # 本句是否因热词触发过复位（防抖）
        self._last_partial_emit_ts: float = 0.0
        self._partial_emit_interval: float = 0.10
        self._auto_reply_seq: int = 0

        self._ui_partial = ui_broadcast_partial
        self._ui_final   = ui_broadcast_final
        self._is_playing = is_playing_now_fn
        self._start_ai   = start_ai_with_text_fn
        self._full_reset = full_system_reset_fn
        self._interrupt_lock = interrupt_lock

    def on_open(self):
        print("[ASR CALLBACK] on_open", flush=True)

    def on_close(self):
        print("[ASR CALLBACK] on_close", flush=True)

    def on_complete(self):
        print("[ASR CALLBACK] on_complete", flush=True)

    def on_error(self, err):
        print(f"[ASR CALLBACK] on_error: {repr(err)}", flush=True)
        try:
            self._post(self._ui_partial(""))
            self._on_sdk_error(str(err))
        except Exception:
            pass

    def on_result(self, result):
        print("[ASR CALLBACK] on_result", flush=True)
        self._handle(result)

    def on_event(self, event):
        print("[ASR CALLBACK] on_event", flush=True)
        self._handle(event)

    def _has_hotword(self, text: str) -> bool:
        t = _normalize_cn(text)
        if not t: return False
        for w in INTERRUPT_KEYWORDS:
            if w and _normalize_cn(w) in t:
                return True
        return False

    def _handle(self, event: Any):
        if ASR_DEBUG_RAW:
            try:
                rawd = _safe_to_dict(event)
                print("[ASR EVENT RAW]", json.dumps(rawd, ensure_ascii=False), flush=True)
            except Exception:
                pass

        text, is_end = _extract_sentence(event)
        if text is None:
            return
        text = text.strip()
        if not text:
            return

        # ---------- ① 热词优先：命中就全清零并短路，绝不送 LLM ----------
        if not self._hot_interrupted and self._has_hotword(text):
            self._hot_interrupted = True

            async def _hot_reset():
                async with self._interrupt_lock:
                    print(f"[ASR HOTWORD] '{text}' -> FULL RESET, skip LLM", flush=True)
                    await self._full_reset("Hotword interrupt")
            try:
                self._post(_hot_reset())
            except Exception:
                pass
            return

        # ---------- ③ final：仅 final 驱动 LLM（若未在播报） ----------
        if is_end is True:
            final_text = text
            try:
                final_ts = time.perf_counter()
                print(f"[PERF] asr_final_received_at={final_ts:.6f}", flush=True)
                print(f"[ASR FINAL]  len={len(final_text)} text='{final_text}'", flush=True)
                self._post(self._ui_final(final_text))
            except Exception:
                pass

            self._start_ai_once(final_text, "final", final_ts)

            # 复位进入下一句
            self._last_partial_for_ui = ""
            self._last_final_text = ""
            self._hot_interrupted = False
            self._auto_reply_seq += 1
            return

        # ---------- ② partial：仅用于 UI 展示，低频刷新 ----------
        now = time.perf_counter()
        changed = text != self._last_partial_for_ui
        meaningful = changed and (
            len(text) >= len(self._last_partial_for_ui) + 2
            or not self._last_partial_for_ui
            or not text.startswith(self._last_partial_for_ui)
        )
        if meaningful and (now - self._last_partial_emit_ts) >= self._partial_emit_interval:
            self._last_partial_for_ui = text
            self._last_partial_emit_ts = now
            try:
                print(f"[ASR PARTIAL] len={len(text)} text='{_shorten(text)}'", flush=True)
                self._post(self._ui_partial(self._last_partial_for_ui))
                self._schedule_auto_reply(text)
            except Exception:
                pass

    def _start_ai_once(self, text: str, reason: str, start_ts: Optional[float] = None):
        final_text = (text or "").strip()
        if not final_text:
            return
        if final_text == self._last_ai_text:
            return
        if self._is_playing():
            return
        self._last_ai_text = final_text

        async def _run():
            try:
                async with self._interrupt_lock:
                    if self._is_playing():
                        return
                    if reason == "auto":
                        print(f"[ASR AUTO FINAL] text='{final_text}'", flush=True)
                        try:
                            await self._ui_final(final_text)
                        except Exception:
                            pass
                    try:
                        if start_ts is not None:
                            print(f"[PERF] asr_{reason}_to_ai_start={(time.perf_counter() - start_ts) * 1000:.1f} ms", flush=True)
                    except Exception:
                        pass
                    print(f"[LLM INPUT TEXT] {final_text}", flush=True)
                    await self._start_ai(final_text)
            except Exception as e:
                print(f"[ASR AI ERROR] failed to start AI reply: {repr(e)}", flush=True)

        try:
            self._post(_run())
        except Exception:
            pass

    def _schedule_auto_reply(self, text: str):
        if ASR_AUTO_REPLY_STABLE_SEC <= 0:
            return
        candidate = (text or "").strip()
        if len(candidate) < 2:
            return
        self._auto_reply_seq += 1
        seq = self._auto_reply_seq
        scheduled_at = time.perf_counter()

        async def _delayed():
            await asyncio.sleep(ASR_AUTO_REPLY_STABLE_SEC)
            if seq != self._auto_reply_seq:
                return
            if candidate != self._last_partial_for_ui:
                return
            if self._hot_interrupted:
                return
            self._start_ai_once(candidate, "auto", scheduled_at)

        try:
            self._post(_delayed())
        except Exception:
            pass

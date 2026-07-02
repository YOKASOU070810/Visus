# -*- coding: utf-8 -*-
"""
Multimodal obstacle alert helpers.

The workflow stays synchronous: it builds alert dicts and returns the latest
emitted alert through ProcessingResult.state_info for app_main.py to broadcast.
"""
import os
import time
from enum import IntEnum
from typing import Any, Dict, Optional


class AlertLevel(IntEnum):
    LOW = 1
    MEDIUM = 2
    HIGH = 3
    CRITICAL = 4


LEVEL_TO_NAME = {
    AlertLevel.LOW: "low",
    AlertLevel.MEDIUM: "medium",
    AlertLevel.HIGH: "high",
    AlertLevel.CRITICAL: "critical",
}

NAME_TO_LEVEL = {v: k for k, v in LEVEL_TO_NAME.items()}

OBSTACLE_NAME_CN = {
    "person": "人",
    "bicycle": "自行车",
    "car": "车",
    "motorcycle": "摩托车",
    "bus": "公交车",
    "truck": "卡车",
    "animal": "动物",
    "scooter": "电瓶车",
    "stroller": "婴儿车",
    "dog": "狗",
    "chair": "椅子",
    "box": "箱子",
    "pole": "柱子",
}


def _env_float(name: str, default: float) -> float:
    try:
        return float(os.getenv(name, str(default)))
    except (TypeError, ValueError):
        return default


def _to_cn_obstacle(name: str) -> str:
    key = (name or "").strip().lower()
    return OBSTACLE_NAME_CN.get(key, "障碍物")


def _direction_for_center(center_x: float, frame_width: int) -> str:
    if frame_width <= 0:
        return "center"
    x_ratio = center_x / float(frame_width)
    left_max = _env_float("VISUS_ALERT_LEFT_MAX", 0.4)
    right_min = _env_float("VISUS_ALERT_RIGHT_MIN", 0.6)
    if x_ratio < left_max:
        return "left"
    if x_ratio > right_min:
        return "right"
    return "center"


def classify_obstacle_alert(obs: Dict[str, Any], frame_width: int, frame_height: int) -> Optional[str]:
    """Return low/medium/high/critical for an obstacle, or None when too far."""
    bottom_y_ratio = float(obs.get("bottom_y_ratio", 0.0) or 0.0)
    area_ratio = float(obs.get("area_ratio", 0.0) or 0.0)
    direction = _direction_for_center(float(obs.get("center_x", frame_width / 2) or 0.0), frame_width)

    low_bottom = _env_float("VISUS_ALERT_LOW_BOTTOM", 0.55)
    low_area = _env_float("VISUS_ALERT_LOW_AREA", 0.02)
    medium_bottom = _env_float("VISUS_ALERT_MEDIUM_BOTTOM", 0.70)
    medium_area = _env_float("VISUS_ALERT_MEDIUM_AREA", 0.05)
    high_bottom = _env_float("VISUS_ALERT_HIGH_BOTTOM", 0.82)
    high_area = _env_float("VISUS_ALERT_HIGH_AREA", 0.10)
    critical_bottom = _env_float("VISUS_ALERT_CRITICAL_BOTTOM", 0.88)
    critical_area = _env_float("VISUS_ALERT_CRITICAL_AREA", 0.16)

    if direction == "center" and (bottom_y_ratio >= critical_bottom or area_ratio >= critical_area):
        return LEVEL_TO_NAME[AlertLevel.CRITICAL]
    if bottom_y_ratio >= high_bottom or area_ratio >= high_area:
        return LEVEL_TO_NAME[AlertLevel.HIGH]
    if bottom_y_ratio >= medium_bottom or area_ratio >= medium_area:
        return LEVEL_TO_NAME[AlertLevel.MEDIUM]
    if bottom_y_ratio >= low_bottom or area_ratio >= low_area:
        return LEVEL_TO_NAME[AlertLevel.LOW]
    return None


def _alert_text(level: str, obstacle_cn: str, direction: str) -> str:
    if level == "critical":
        return "正前方障碍物很近，请立即停下"
    if direction == "left":
        return f"前方偏左有{obstacle_cn}，请注意避让"
    if direction == "right":
        return f"前方偏右有{obstacle_cn}，请注意避让"
    if level == "low":
        return "前方发现障碍物，请注意"
    if level == "medium":
        return "前方有障碍物，请注意避让"
    if level == "high":
        return "前方障碍物较近，请减速"
    return f"正前方有{obstacle_cn}，请停一下"


def build_multimodal_alert(obs: Dict[str, Any], frame_width: int, frame_height: int) -> Optional[Dict[str, Any]]:
    level = classify_obstacle_alert(obs, frame_width, frame_height)
    if level is None:
        return None

    name = str(obs.get("name", "obstacle") or "obstacle")
    obstacle_cn = _to_cn_obstacle(name)
    direction = _direction_for_center(float(obs.get("center_x", frame_width / 2) or 0.0), frame_width)
    return {
        "type": "multimodal_alert",
        "level": level,
        "text": _alert_text(level, obstacle_cn, direction),
        "obstacle": name,
        "obstacle_cn": obstacle_cn,
        "direction": direction,
        "bottom_y_ratio": float(obs.get("bottom_y_ratio", 0.0) or 0.0),
        "area_ratio": float(obs.get("area_ratio", 0.0) or 0.0),
        "speak": False,
        "vibrate": True,
        "source": "safety_monitor",
        "ts": time.time(),
    }


def build_obstacle_alert(obs: Dict[str, Any], frame_width: int, frame_height: int) -> Optional[Dict[str, Any]]:
    return build_multimodal_alert(obs, frame_width, frame_height)


def should_emit_alert(alert: Dict[str, Any], previous_state: Dict[str, Any]) -> bool:
    """Throttle repeated alerts while allowing immediate level escalation."""
    if not alert:
        return False

    now = time.time()
    level_name = str(alert.get("level", "low"))
    level = NAME_TO_LEVEL.get(level_name, AlertLevel.LOW)
    key = f"{alert.get('obstacle', 'obstacle')}:{alert.get('direction', 'center')}"

    last_key = previous_state.get("last_key")
    last_level = NAME_TO_LEVEL.get(str(previous_state.get("last_level", "low")), AlertLevel.LOW)
    last_time = float(previous_state.get("last_time", 0.0) or 0.0)

    cooldowns = {
        "low": _env_float("VISUS_ALERT_LOW_COOLDOWN", 8.0),
        "medium": _env_float("VISUS_ALERT_MEDIUM_COOLDOWN", 5.0),
        "high": _env_float("VISUS_ALERT_HIGH_COOLDOWN", 3.0),
        "critical": _env_float("VISUS_ALERT_CRITICAL_COOLDOWN", 1.5),
    }
    cooldown = cooldowns.get(level_name, 6.0)

    should_emit = False
    if key != last_key:
        should_emit = True
    elif level > last_level:
        should_emit = True
    elif now - last_time >= cooldown:
        should_emit = True

    if should_emit:
        previous_state["last_key"] = key
        previous_state["last_level"] = level_name
        previous_state["last_time"] = now

    return should_emit


def get_alert_cooldown(level_name: str) -> float:
    cooldowns = {
        "low": _env_float("VISUS_ALERT_LOW_COOLDOWN", 8.0),
        "medium": _env_float("VISUS_ALERT_MEDIUM_COOLDOWN", 5.0),
        "high": _env_float("VISUS_ALERT_HIGH_COOLDOWN", 3.0),
        "critical": _env_float("VISUS_ALERT_CRITICAL_COOLDOWN", 1.5),
    }
    return cooldowns.get(level_name, 5.0)


class MultimodalAlertManager:
    """Stateful active safety alert throttler."""

    def __init__(self):
        self.state: Dict[str, Any] = {}
        self.last_seen_ts = 0.0
        self.disappear_reset_sec = _env_float("VISUS_ALERT_DISAPPEAR_RESET_SEC", 2.5)

    def process_obstacles(self, obstacles, frame_width: int, frame_height: int) -> Optional[Dict[str, Any]]:
        now = time.time()
        if not obstacles:
            if self.last_seen_ts and now - self.last_seen_ts >= self.disappear_reset_sec:
                self.state.clear()
                self.last_seen_ts = 0.0
            print("[MULTIMODAL_ALERT] no obstacle", flush=True)
            return None

        self.last_seen_ts = now
        candidates = []
        for obs in obstacles:
            alert = build_multimodal_alert(obs, frame_width, frame_height)
            if alert:
                candidates.append(alert)

        if not candidates:
            print("[MULTIMODAL_ALERT] no obstacle", flush=True)
            return None

        level_rank = {"low": 1, "medium": 2, "high": 3, "critical": 4}
        alert = max(
            candidates,
            key=lambda item: (
                level_rank.get(item.get("level", "low"), 0),
                float(item.get("bottom_y_ratio", 0.0)),
                float(item.get("area_ratio", 0.0)),
            ),
        )

        if should_emit_alert(alert, self.state):
            print(
                f"[MULTIMODAL_ALERT] emit level={alert.get('level')} "
                f"direction={alert.get('direction')} text={alert.get('text')}",
                flush=True,
            )
            return alert

        remaining = max(
            0.0,
            get_alert_cooldown(str(alert.get("level", "medium"))) - (now - float(self.state.get("last_time", now))),
        )
        print(
            f"[MULTIMODAL_ALERT] skip cooldown level={alert.get('level')} remaining={remaining:.2f}s",
            flush=True,
        )
        return None

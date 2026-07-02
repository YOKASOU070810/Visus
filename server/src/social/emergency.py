# emergency.py - Emergency/Fall detection integration with the AI vision system
"""
Emergency Detection Module

This module bridges the AI vision system with the social/alert system.
When the vision AI detects a potential fall or emergency situation,
it triggers an automatic status change and pushes alerts to all friends.

Architecture:
  Vision System (YOLO / pose estimation)
    -> EmergencyDetector.evaluate()
    -> EmergencyDetector.trigger_emergency()
    -> POST /api/emergency/trigger/
    -> WebSocket push to all friends

Currently the fall detection is a PLACEHOLDER module. The actual fall detection
logic will be implemented using:
  - YOLO pose estimation (keypoint analysis for sudden vertical changes)
  - Accelerometer data from the phone/ESP32 IMU
  - Voice trigger ("help", "救命", etc.)
"""

import logging
import time
from typing import Optional, Dict, Any
from collections import deque
from dataclasses import dataclass, field

logger = logging.getLogger("visus.emergency")


@dataclass
class EmergencyConfig:
    """Configuration for emergency detection thresholds."""
    # Fall detection
    fall_detection_enabled: bool = True
    fall_confidence_threshold: float = 0.75  # minimum confidence to trigger
    fall_cooldown_seconds: float = 30.0  # prevent repeated triggers

    # Voice trigger keywords that indicate emergency
    voice_trigger_keywords: list = field(default_factory=lambda: [
        "救命", "help", "帮帮我", "我不行了", "摔倒", "fall", "emergency",
        "sos", "紧急", "快来人", "有人吗", "救救我",
    ])

    # Obstacle collision detection
    collision_detection_enabled: bool = True
    collision_proximity_threshold: float = 0.3  # object covering >30% of frame


class EmergencyDetector:
    """Evaluates AI vision outputs and triggers emergency alerts when needed.

    Usage:
        detector = EmergencyDetector()

        # Called from the vision pipeline when processing each frame
        result = detector.evaluate_frame(
            user_id=user_id,
            frame_analysis=nav_result,  # from NavigationMaster.process_frame()
            voice_text=final_asr_text,   # from ASR final result
        )

        if result.should_trigger:
            # Emergency detected! The API route handles the push
            await detector.trigger_emergency(...)
    """

    def __init__(self, config: Optional[EmergencyConfig] = None):
        self.config = config or EmergencyConfig()
        self._last_trigger_time: Dict[int, float] = {}  # user_id -> timestamp
        self._fall_score_history: Dict[int, deque] = {}  # user_id -> recent scores

    def evaluate_frame(
        self,
        user_id: int,
        frame_analysis: Optional[Any] = None,
        voice_text: Optional[str] = None,
        imu_data: Optional[dict] = None,
    ) -> "EmergencyEvaluation":
        """Evaluate whether current frame/situation indicates an emergency.

        Args:
            user_id: The current user ID
            frame_analysis: Result from NavigationMaster.process_frame() or similar
            voice_text: Final ASR text from the user's speech
            imu_data: Accelerometer/gyroscope data from the device

        Returns:
            EmergencyEvaluation with should_trigger flag and details
        """
        evaluation = EmergencyEvaluation()

        # Check cooldown
        last_time = self._last_trigger_time.get(user_id, 0)
        if time.time() - last_time < self.config.fall_cooldown_seconds:
            return evaluation  # still in cooldown

        # 1. Check voice triggers
        if voice_text and self.config.fall_detection_enabled:
            evaluation.voice_trigger_detected = self._check_voice_trigger(voice_text)
            if evaluation.voice_trigger_detected:
                evaluation.should_trigger = True
                evaluation.event_type = "voice_trigger"
                evaluation.severity = "high"
                evaluation.description = f"User said: '{voice_text}'"
                return evaluation

        # 2. Check IMU data for sudden acceleration changes (fall signature)
        if imu_data and self.config.fall_detection_enabled:
            evaluation.fall_from_imu = self._check_imu_fall(imu_data, user_id)
            if evaluation.fall_from_imu:
                evaluation.should_trigger = True
                evaluation.event_type = "fall"
                evaluation.severity = "critical"
                evaluation.description = "Sudden acceleration detected (possible fall)"
                return evaluation

        # 3. Placeholder for vision-based fall detection
        #    (will analyze YOLO pose keypoints for person-down detection)
        if frame_analysis and self.config.fall_detection_enabled:
            evaluation.fall_from_vision = self._check_vision_fall(frame_analysis, user_id)
            if evaluation.fall_from_vision:
                evaluation.should_trigger = True
                evaluation.event_type = "fall"
                evaluation.severity = "critical"
                evaluation.description = "AI vision detected person on ground"
                return evaluation

        # 4. Check for obstacle collision
        if frame_analysis and self.config.collision_detection_enabled:
            evaluation.collision_detected = self._check_collision(frame_analysis)
            if evaluation.collision_detected:
                evaluation.should_trigger = True
                evaluation.event_type = "obstacle_collision"
                evaluation.severity = "high"
                evaluation.description = "Obstacle collision detected"
                return evaluation

        return evaluation

    def _check_voice_trigger(self, text: str) -> bool:
        """Check if the spoken text contains emergency keywords."""
        text_lower = text.lower()
        for keyword in self.config.voice_trigger_keywords:
            if keyword.lower() in text_lower:
                return True
        return False

    def _check_imu_fall(self, imu_data: dict, user_id: int) -> bool:
        """Check IMU data for fall signatures (sudden large acceleration).

        A fall typically shows:
        - Rapid acceleration change > 3g
        - Followed by a period of no movement
        """
        # PLACEHOLDER: actual implementation will use accelerometer magnitude
        accel = imu_data.get("acceleration", {})
        magnitude = (accel.get("x", 0)**2 + accel.get("y", 0)**2 + accel.get("z", 0)**2) ** 0.5

        # Free-fall or impact threshold (in m/s², ~3g ≈ 29.4)
        FALL_THRESHOLD = 25.0
        return magnitude > FALL_THRESHOLD

    def _check_vision_fall(self, frame_analysis: Any, user_id: int) -> bool:
        """Check vision analysis for person-down detection.

        PLACEHOLDER: Will use YOLO pose keypoints to detect:
        - Person's head y-coordinate suddenly near ground level
        - Person bounding box aspect ratio change (wide + short = lying down)
        """
        # Track fall confidence over time for stability
        if user_id not in self._fall_score_history:
            self._fall_score_history[user_id] = deque(maxlen=10)

        # Placeholder: check if frame_analysis has fall-related attributes
        fall_score = getattr(frame_analysis, 'fall_confidence', 0.0)
        self._fall_score_history[user_id].append(fall_score)

        # Require consistent high confidence over recent frames
        if len(self._fall_score_history[user_id]) >= 3:
            recent_scores = list(self._fall_score_history[user_id])[-5:]
            avg_score = sum(recent_scores) / len(recent_scores) if recent_scores else 0
            return avg_score > self.config.fall_confidence_threshold

        return False

    def _check_collision(self, frame_analysis: Any) -> bool:
        """Check if an obstacle collision likely occurred."""
        obstacle_ratio = getattr(frame_analysis, 'obstacle_coverage_ratio', 0.0)
        return obstacle_ratio > self.config.collision_proximity_threshold

    def record_trigger(self, user_id: int):
        """Record that an emergency was triggered for cooldown tracking."""
        self._last_trigger_time[user_id] = time.time()

    async def trigger_emergency(
        self,
        user_id: int,
        event_type: str = "fall",
        severity: str = "high",
        latitude: float = 0.0,
        longitude: float = 0.0,
        city: str = "",
        description: str = "",
    ) -> dict:
        """Trigger emergency alert through the social API.

        This is the main entry point called by the vision system.
        It calls the internal API to create the emergency event and push to friends.
        """
        from .database import SessionLocal, EmergencyEvent, SafetyAlert, User
        from .status_ws import status_manager

        db = SessionLocal()
        try:
            # Create emergency event
            event = EmergencyEvent(
                user_id=user_id,
                event_type=event_type,
                severity=severity,
                latitude=latitude,
                longitude=longitude,
                city=city,
                description=description,
            )
            db.add(event)

            # Auto-update safety status to NOT SAFE
            alert = SafetyAlert(
                user_id=user_id,
                status=False,
                alert_type=f"emergency_{event_type}",
                latitude=latitude,
                longitude=longitude,
                city=city,
                note=f"[EMERGENCY] {description}" if description else f"[EMERGENCY] {event_type} detected by AI",
            )
            db.add(alert)
            db.commit()
            db.refresh(alert)
            db.refresh(event)

            # Get user info
            user = db.query(User).filter(User.id == user_id).first()

            # Record trigger time for cooldown
            self.record_trigger(user_id)

            # Broadcast to all friends via WebSocket
            await status_manager.broadcast_emergency(
                user_id,
                user.to_dict() if user else {"id": user_id},
                alert.to_dict(),
                event.to_dict(),
            )

            logger.info(f"[EMERGENCY] Triggered for user {user_id}: {event_type} (severity={severity})")
            return {"success": True, "event": event.to_dict(), "alert": alert.to_dict()}

        except Exception as e:
            logger.error(f"[EMERGENCY] Failed to trigger: {e}")
            return {"success": False, "error": str(e)}
        finally:
            db.close()


@dataclass
class EmergencyEvaluation:
    """Result of emergency evaluation for a single frame."""
    should_trigger: bool = False
    event_type: str = ""
    severity: str = "high"
    description: str = ""
    voice_trigger_detected: bool = False
    fall_from_imu: bool = False
    fall_from_vision: bool = False
    collision_detected: bool = False


# Global instance
emergency_detector = EmergencyDetector()

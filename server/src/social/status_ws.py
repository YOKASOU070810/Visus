# status_ws.py - WebSocket manager for real-time status push notifications
import asyncio
import json
import logging
from typing import Dict, Set, Optional

from fastapi import WebSocket, WebSocketDisconnect
from starlette.websockets import WebSocketState

from .auth import decode_token

logger = logging.getLogger("visus.status_ws")


class StatusWebSocketManager:
    """Manages WebSocket connections for real-time social status updates.

    Each connected client is tracked by user_id.
    When a friend's status changes, all connected friends get pushed the update.
    """

    def __init__(self):
        # user_id -> set of WebSocket connections
        self._connections: Dict[int, Set[WebSocket]] = {}
        self._lock = asyncio.Lock()

    async def connect(self, websocket: WebSocket, user_id: int):
        """Register a new WebSocket connection for a user."""
        await websocket.accept()
        async with self._lock:
            if user_id not in self._connections:
                self._connections[user_id] = set()
            self._connections[user_id].add(websocket)
        logger.info(f"[StatusWS] User {user_id} connected ({len(self._connections[user_id])} connections)")

    async def disconnect(self, websocket: WebSocket, user_id: int):
        """Remove a WebSocket connection."""
        async with self._lock:
            if user_id in self._connections:
                self._connections[user_id].discard(websocket)
                if not self._connections[user_id]:
                    del self._connections[user_id]
        logger.info(f"[StatusWS] User {user_id} disconnected")

    async def broadcast_user_status(self, user_id: int, alert_data: dict):
        """Broadcast a user's status change to all connected friends.

        Called after any status update (manual or emergency).
        The receiver determines which friends need the update.
        """
        message = json.dumps({
            "type": "status_update",
            "user_id": user_id,
            "alert": alert_data,
        })
        # Broadcast to ALL connected users - each client filters by their friend list
        async with self._lock:
            for uid, connections in list(self._connections.items()):
                for ws in list(connections):
                    try:
                        if ws.application_state == WebSocketState.CONNECTED:
                            await ws.send_text(message)
                    except Exception:
                        connections.discard(ws)

        logger.info(f"[StatusWS] Broadcast status update for user {user_id}")

    async def broadcast_emergency(self, user_id: int, user_data: dict, alert_data: dict, event_data: dict):
        """Broadcast emergency alert to all connected users.

        This is higher priority - it sends complete details about the emergency.
        """
        message = json.dumps({
            "type": "emergency_alert",
            "user_id": user_id,
            "user": user_data,
            "alert": alert_data,
            "event": event_data,
        })
        async with self._lock:
            for uid, connections in list(self._connections.items()):
                for ws in list(connections):
                    try:
                        if ws.application_state == WebSocketState.CONNECTED:
                            await ws.send_text(message)
                    except Exception:
                        connections.discard(ws)

        logger.info(f"[StatusWS] Broadcast EMERGENCY for user {user_id}")

    async def notify_friend_request(self, receiver_id: int, request_data: dict):
        """Send a friend request notification to a specific user."""
        message = json.dumps({
            "type": "friend_request",
            "request": request_data,
        })
        async with self._lock:
            connections = self._connections.get(receiver_id, set())
            for ws in list(connections):
                try:
                    if ws.application_state == WebSocketState.CONNECTED:
                        await ws.send_text(message)
                except Exception:
                    connections.discard(ws)

        logger.info(f"[StatusWS] Notified friend request to user {receiver_id}")

    async def send_to_user(self, user_id: int, message: dict):
        """Send a custom message to a specific user's connections."""
        text = json.dumps(message)
        async with self._lock:
            connections = self._connections.get(user_id, set())
            for ws in list(connections):
                try:
                    if ws.application_state == WebSocketState.CONNECTED:
                        await ws.send_text(text)
                except Exception:
                    connections.discard(ws)


# Singleton
status_manager = StatusWebSocketManager()


async def handle_status_websocket(websocket: WebSocket):
    """WebSocket endpoint handler for /ws/social.

    Client connects with ?token=JWT_TOKEN query parameter.
    Receives real-time: status updates, friend request notifications, emergency alerts.
    """
    token = websocket.query_params.get("token")
    if not token:
        await websocket.close(code=4001, reason="Missing token")
        return

    user_id = decode_token(token)
    if not user_id:
        await websocket.close(code=4002, reason="Invalid token")
        return

    await status_manager.connect(websocket, user_id)

    try:
        # Keep connection alive, handle incoming messages
        while websocket.application_state == WebSocketState.CONNECTED:
            try:
                data = await asyncio.wait_for(websocket.receive_text(), timeout=30)
                # client can send keepalive pings or status requests
                msg = json.loads(data)
                msg_type = msg.get("type", "")

                if msg_type == "ping":
                    await websocket.send_text(json.dumps({"type": "pong"}))
                elif msg_type == "subscribe_friends":
                    # client tells server which friend IDs to track (for targeted push)
                    pass  # currently broadcasts to all; future optimization
            except asyncio.TimeoutError:
                # send keepalive
                try:
                    await websocket.send_text(json.dumps({"type": "keepalive"}))
                except Exception:
                    break
            except WebSocketDisconnect:
                break
            except Exception as e:
                logger.warning(f"[StatusWS] Error receiving message: {e}")
                break
    finally:
        await status_manager.disconnect(websocket, user_id)

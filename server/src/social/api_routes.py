# api_routes.py - FastAPI routes for social features (auth, friends, status, emergency)
import json
from datetime import datetime, timezone
from typing import Optional

from fastapi import APIRouter, Request, HTTPException, Query
from pydantic import BaseModel, EmailStr
from sqlalchemy import or_

from .database import (
    SessionLocal, User, SafetyAlert, FriendRequest, Friendship, EmergencyEvent
)
from .auth import create_token, get_current_user, get_optional_user

router = APIRouter(prefix="/api", tags=["social"])

# ── Pydantic models ──
class LoginRequest(BaseModel):
    email: str
    password: str

class SignupRequest(BaseModel):
    email: str
    password: str
    first_name: str = ""
    last_name: str = ""
    user_type: str = "blind"  # "blind" or "family"

class StatusUpdateRequest(BaseModel):
    status: bool = True
    latitude: float = 0.0
    longitude: float = 0.0
    city: str = ""
    alert_type: str = "manual"
    note: str = ""

class FriendActionRequest(BaseModel):
    user_id: int

class SearchRequest(BaseModel):
    query: str = ""

class EmergencyTriggerRequest(BaseModel):
    event_type: str = "fall"  # fall, obstacle_collision, manual_sos, voice_trigger
    severity: str = "high"
    latitude: float = 0.0
    longitude: float = 0.0
    city: str = ""
    description: str = ""


def api_ok(data=None, status_code=200):
    return {"success": True, "data": data}


def api_error(error: str, status_code=400):
    raise HTTPException(status_code=status_code, detail={"success": False, "error": error})


# ── Auth ──

@router.post("/login/")
async def api_login(body: LoginRequest):
    db = SessionLocal()
    try:
        user = db.query(User).filter(User.email == body.email).first()
        if not user or not user.check_password(body.password):
            raise HTTPException(status_code=401,
                detail={"success": False, "error": "Invalid credentials"})
        user.last_login = datetime.now(timezone.utc)
        db.commit()

        token = create_token(user.id)
        return {
            "success": True,
            "data": {
                "token": token,
                "user": user.to_dict(),
            }
        }
    finally:
        db.close()


@router.post("/signup/")
async def api_signup(body: SignupRequest):
    db = SessionLocal()
    try:
        if db.query(User).filter(User.email == body.email).first():
            raise HTTPException(status_code=400,
                detail={"success": False, "error": "Email already registered"})

        user = User(
            username=body.email,  # use email as username
            email=body.email,
            first_name=body.first_name,
            last_name=body.last_name,
            user_type=body.user_type,
        )
        user.set_password(body.password)
        db.add(user)
        db.commit()
        db.refresh(user)

        # create initial safety status
        alert = SafetyAlert(user_id=user.id, status=True, alert_type="manual")
        db.add(alert)
        db.commit()

        token = create_token(user.id)
        return {
            "success": True,
            "data": {
                "token": token,
                "user": user.to_dict(),
            }
        }
    finally:
        db.close()


@router.get("/profile/")
async def api_profile(request: Request):
    user = get_current_user(request)
    db = SessionLocal()
    try:
        latest = db.query(SafetyAlert).filter(
            SafetyAlert.user_id == user.id
        ).order_by(SafetyAlert.last_updated.desc()).first()

        return api_ok({
            "user": user.to_dict(),
            "status": latest.to_dict() if latest else None,
        })
    finally:
        db.close()


@router.post("/profile/switch-mode")
async def api_switch_mode(request: Request):
    """Switch user type between blind and family. Persists in database."""
    user = get_current_user(request)
    db = SessionLocal()
    try:
        new_type = "blind" if user.user_type == "family" else "family"
        db.query(User).filter(User.id == user.id).update({"user_type": new_type})
        db.commit()
        return api_ok({
            "user_type": new_type,
            "message": f"Switched to {new_type} mode"
        })
    finally:
        db.close()


# ── Safety Status ──

@router.get("/status/")
async def api_get_status(request: Request):
    user = get_current_user(request)
    db = SessionLocal()
    try:
        latest = db.query(SafetyAlert).filter(
            SafetyAlert.user_id == user.id
        ).order_by(SafetyAlert.last_updated.desc()).first()
        return api_ok({
            "my_status": latest.to_dict() if latest else None,
        })
    finally:
        db.close()


@router.post("/status/update/")
async def api_update_status(body: StatusUpdateRequest, request: Request):
    user = get_current_user(request)
    db = SessionLocal()
    try:
        alert = SafetyAlert(
            user_id=user.id,
            status=body.status,
            alert_type=body.alert_type,
            latitude=body.latitude,
            longitude=body.longitude,
            city=body.city,
            note=body.note,
        )
        db.add(alert)
        db.commit()
        db.refresh(alert)

        # Broadcast to WebSocket manager
        from .status_ws import status_manager
        await status_manager.broadcast_user_status(user.id, alert.to_dict())

        return api_ok({"alert": alert.to_dict()}, status_code=201)
    finally:
        db.close()


# ── Friends ──

@router.get("/friends/")
async def api_get_friends(request: Request):
    user = get_current_user(request)
    db = SessionLocal()
    try:
        friendships = db.query(Friendship).filter(
            or_(Friendship.user1_id == user.id, Friendship.user2_id == user.id)
        ).all()

        result = []
        for f in friendships:
            friend_id = f.user2_id if f.user1_id == user.id else f.user1_id
            friend = db.query(User).filter(User.id == friend_id).first()
            if not friend:
                continue
            latest = db.query(SafetyAlert).filter(
                SafetyAlert.user_id == friend_id
            ).order_by(SafetyAlert.last_updated.desc()).first()

            result.append({
                "user": friend.to_dict(),
                "status": latest.status if latest else None,
                "alert_type": latest.alert_type if latest else None,
                "latitude": latest.latitude if latest else None,
                "longitude": latest.longitude if latest else None,
                "city": latest.city if latest else None,
                "note": latest.note if latest else None,
                "last_updated": latest.last_updated.isoformat() if latest and latest.last_updated else None,
            })
        return api_ok({"friends": result})
    finally:
        db.close()


@router.post("/friends/add/")
async def api_add_friend(body: FriendActionRequest, request: Request):
    user = get_current_user(request)
    db = SessionLocal()
    try:
        if body.user_id == user.id:
            raise HTTPException(status_code=400,
                detail={"success": False, "error": "Cannot add yourself"})

        friend = db.query(User).filter(User.id == body.user_id).first()
        if not friend:
            raise HTTPException(status_code=404,
                detail={"success": False, "error": "User not found"})

        # check existing request
        existing_req = db.query(FriendRequest).filter(
            FriendRequest.sender_id == user.id,
            FriendRequest.receiver_id == body.user_id,
            FriendRequest.is_pending == True,
        ).first()
        if existing_req:
            raise HTTPException(status_code=400,
                detail={"success": False, "error": "Friend request already sent"})

        # check existing friendship
        existing_friendship = db.query(Friendship).filter(
            or_(
                (Friendship.user1_id == user.id) & (Friendship.user2_id == body.user_id),
                (Friendship.user1_id == body.user_id) & (Friendship.user2_id == user.id),
            )
        ).first()
        if existing_friendship:
            raise HTTPException(status_code=400,
                detail={"success": False, "error": "Already friends"})

        req = FriendRequest(sender_id=user.id, receiver_id=body.user_id)
        db.add(req)
        db.commit()

        # notify receiver via WebSocket
        from .status_ws import status_manager
        await status_manager.notify_friend_request(body.user_id, req.to_dict())

        return api_ok({"message": "Friend request sent"})
    finally:
        db.close()


@router.post("/friends/remove/")
async def api_remove_friend(body: FriendActionRequest, request: Request):
    user = get_current_user(request)
    db = SessionLocal()
    try:
        deleted = db.query(Friendship).filter(
            or_(
                (Friendship.user1_id == user.id) & (Friendship.user2_id == body.user_id),
                (Friendship.user1_id == body.user_id) & (Friendship.user2_id == user.id),
            )
        ).delete()
        db.commit()

        if deleted == 0:
            raise HTTPException(status_code=404,
                detail={"success": False, "error": "Friendship not found"})

        return api_ok({"message": "Friend removed"})
    finally:
        db.close()


# ── Friend Requests ──

@router.get("/friends/requests/")
async def api_get_requests(request: Request):
    user = get_current_user(request)
    db = SessionLocal()
    try:
        pending = db.query(FriendRequest).filter(
            FriendRequest.receiver_id == user.id,
            FriendRequest.is_pending == True,
        ).all()

        result = []
        for r in pending:
            sender = db.query(User).filter(User.id == r.sender_id).first()
            result.append({
                "id": r.id,
                "sender": sender.to_dict() if sender else None,
                "created_at": r.created_at.isoformat() if r.created_at else None,
            })
        return api_ok({"requests": result})
    finally:
        db.close()


@router.post("/friends/requests/{request_id}/{action}/")
async def api_respond_request(request_id: int, action: str, request: Request):
    if action not in ("approve", "decline"):
        raise HTTPException(status_code=400,
            detail={"success": False, "error": "Invalid action"})

    user = get_current_user(request)
    db = SessionLocal()
    try:
        fr = db.query(FriendRequest).filter(
            FriendRequest.id == request_id,
            FriendRequest.receiver_id == user.id,
            FriendRequest.is_pending == True,
        ).first()

        if not fr:
            raise HTTPException(status_code=404,
                detail={"success": False, "error": "Request not found"})

        if action == "approve":
            fr.is_pending = False
            # create friendship
            friendship = Friendship(user1_id=fr.sender_id, user2_id=fr.receiver_id)
            db.add(friendship)
            db.commit()
            return api_ok({"message": "Friend request approved"})
        else:
            db.delete(fr)
            db.commit()
            return api_ok({"message": "Friend request declined"})
    finally:
        db.close()


# ── Search ──

@router.post("/search/")
async def api_search_users(body: SearchRequest, request: Request):
    user = get_current_user(request)
    db = SessionLocal()
    try:
        query = f"%{body.query}%"
        users = db.query(User).filter(
            User.username.ilike(query),
            User.id != user.id,
        ).limit(20).all()

        # get friend IDs
        friendships = db.query(Friendship).filter(
            or_(Friendship.user1_id == user.id, Friendship.user2_id == user.id)
        ).all()
        friend_ids = set()
        for f in friendships:
            friend_ids.add(f.user2_id if f.user1_id == user.id else f.user1_id)

        # get pending sent requests
        pending_sent = set(
            r.receiver_id for r in db.query(FriendRequest).filter(
                FriendRequest.sender_id == user.id,
                FriendRequest.is_pending == True,
            ).all()
        )

        result = []
        for u in users:
            result.append({
                "user": u.to_dict(),
                "is_friend": u.id in friend_ids,
                "request_pending": u.id in pending_sent,
            })
        return api_ok({"users": result})
    finally:
        db.close()


# ── Emergency ──

@router.post("/emergency/trigger/")
async def api_trigger_emergency(body: EmergencyTriggerRequest, request: Request):
    """Called by AI vision system when fall or emergency is detected."""
    user = get_current_user(request)
    db = SessionLocal()
    try:
        # Create emergency event
        event = EmergencyEvent(
            user_id=user.id,
            event_type=body.event_type,
            severity=body.severity,
            latitude=body.latitude,
            longitude=body.longitude,
            city=body.city,
            description=body.description,
        )
        db.add(event)

        # Auto-update safety status to NOT SAFE
        alert = SafetyAlert(
            user_id=user.id,
            status=False,  # NOT SAFE
            alert_type=f"emergency_{body.event_type}",
            latitude=body.latitude,
            longitude=body.longitude,
            city=body.city,
            note=f"[EMERGENCY] {body.description}" if body.description else f"[EMERGENCY] {body.event_type} detected",
        )
        db.add(alert)
        db.commit()
        db.refresh(alert)
        db.refresh(event)

        # Broadcast emergency to all friends via WebSocket
        from .status_ws import status_manager
        await status_manager.broadcast_emergency(user.id, user.to_dict(), alert.to_dict(), event.to_dict())

        return api_ok({
            "event": event.to_dict(),
            "alert": alert.to_dict(),
        }, status_code=201)
    finally:
        db.close()


@router.get("/emergency/history/")
async def api_get_emergency_history(request: Request):
    """Get current user's emergency history."""
    user = get_current_user(request)
    db = SessionLocal()
    try:
        events = db.query(EmergencyEvent).filter(
            EmergencyEvent.user_id == user.id,
        ).order_by(EmergencyEvent.created_at.desc()).limit(50).all()

        return api_ok({
            "events": [e.to_dict() for e in events],
        })
    finally:
        db.close()


@router.post("/emergency/{event_id}/resolve/")
async def api_resolve_emergency(event_id: int, request: Request):
    """Mark an emergency as resolved."""
    user = get_current_user(request)
    db = SessionLocal()
    try:
        event = db.query(EmergencyEvent).filter(
            EmergencyEvent.id == event_id,
            EmergencyEvent.user_id == user.id,
        ).first()
        if not event:
            raise HTTPException(status_code=404,
                detail={"success": False, "error": "Event not found"})

        event.is_resolved = True
        event.resolved_at = datetime.now(timezone.utc)

        # restore safety status
        alert = SafetyAlert(
            user_id=user.id,
            status=True,
            alert_type="manual",
            note="Emergency resolved",
        )
        db.add(alert)
        db.commit()
        db.refresh(alert)

        # broadcast status change
        from .status_ws import status_manager
        await status_manager.broadcast_user_status(user.id, alert.to_dict())

        return api_ok({"event": event.to_dict()})
    finally:
        db.close()

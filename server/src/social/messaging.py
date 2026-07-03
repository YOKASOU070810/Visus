# messaging.py - Private messaging (1-on-1) + family relationship management
from fastapi import APIRouter, HTTPException, Request
from pydantic import BaseModel
from sqlalchemy import or_, and_, desc

from .database import SessionLocal, Friendship, FriendMessage, User
from .auth import get_current_user
from .status_ws import status_manager

router = APIRouter(prefix="/api", tags=["messaging"])


class SendMessageRequest(BaseModel):
    receiver_id: int
    content: str
    msg_type: str = "text"  # text, location
    latitude: float = 0.0
    longitude: float = 0.0


class FamilyRequest(BaseModel):
    friend_id: int


# ── Private Messages ──

@router.post("/messages/send")
async def send_private_message(body: SendMessageRequest, request: Request):
    """Send a private message to a friend."""
    user = get_current_user(request)
    db = SessionLocal()
    try:
        # Verify friendship
        friendship = db.query(Friendship).filter(
            or_(
                and_(Friendship.user1_id == user.id, Friendship.user2_id == body.receiver_id),
                and_(Friendship.user1_id == body.receiver_id, Friendship.user2_id == user.id),
            )
        ).first()
        if not friendship:
            raise HTTPException(status_code=403, detail={"success": False, "error": "Not friends"})

        msg = FriendMessage(
            sender_id=user.id,
            receiver_id=body.receiver_id,
            content=body.content,
            msg_type=body.msg_type,
            latitude=body.latitude,
            longitude=body.longitude,
        )
        db.add(msg)
        db.commit()
        db.refresh(msg)

        # Push to receiver via WebSocket
        msg_dict = msg.to_dict()
        await status_manager.send_to_user(body.receiver_id, {
            "type": "private_message",
            "message": msg_dict,
        })

        return {"success": True, "data": {"message": msg_dict}}
    finally:
        db.close()


@router.get("/messages/unread")
async def get_unread_count(request: Request):
    """Get count of unread private messages."""
    user = get_current_user(request)
    db = SessionLocal()
    try:
        count = db.query(FriendMessage).filter(
            FriendMessage.receiver_id == user.id,
            FriendMessage.is_read == False,
        ).count()
        return {"success": True, "data": {"unread": count}}
    finally:
        db.close()


@router.get("/messages/{friend_id}")
async def get_private_messages(friend_id: int, request: Request, limit: int = 50):
    """Get conversation history with a specific friend."""
    user = get_current_user(request)
    db = SessionLocal()
    try:
        msgs = db.query(FriendMessage).filter(
            or_(
                and_(FriendMessage.sender_id == user.id, FriendMessage.receiver_id == friend_id),
                and_(FriendMessage.sender_id == friend_id, FriendMessage.receiver_id == user.id),
            )
        ).order_by(desc(FriendMessage.created_at)).limit(limit).all()

        # Mark as read
        for m in msgs:
            if m.receiver_id == user.id and not m.is_read:
                m.is_read = True
        db.commit()

        return {"success": True, "data": {"messages": [m.to_dict() for m in reversed(msgs)]}}
    finally:
        db.close()


# ── Family Relations ──

@router.post("/family/set")
async def set_family(body: FamilyRequest, request: Request):
    """Set a friend as family member (grants special permissions)."""
    user = get_current_user(request)
    db = SessionLocal()
    try:
        friendship = db.query(Friendship).filter(
            or_(
                and_(Friendship.user1_id == user.id, Friendship.user2_id == body.friend_id),
                and_(Friendship.user1_id == body.friend_id, Friendship.user2_id == user.id),
            )
        ).first()
        if not friendship:
            raise HTTPException(status_code=404, detail={"success": False, "error": "Friendship not found"})

        friendship.is_family = True
        db.commit()

        await status_manager.send_to_user(body.friend_id, {
            "type": "family_set",
            "by_user_id": user.id,
            "by_user_name": f"{user.first_name} {user.last_name}".strip(),
        })

        return {"success": True, "data": {"message": "Set as family"}}
    finally:
        db.close()


@router.post("/family/unset")
async def unset_family(body: FamilyRequest, request: Request):
    """Remove family designation."""
    user = get_current_user(request)
    db = SessionLocal()
    try:
        friendship = db.query(Friendship).filter(
            or_(
                and_(Friendship.user1_id == user.id, Friendship.user2_id == body.friend_id),
                and_(Friendship.user1_id == body.friend_id, Friendship.user2_id == user.id),
            )
        ).first()
        if not friendship:
            raise HTTPException(status_code=404, detail={"success": False, "error": "Friendship not found"})

        friendship.is_family = False
        db.commit()
        return {"success": True, "data": {"message": "Family unset"}}
    finally:
        db.close()


@router.get("/family/list")
async def list_family(request: Request):
    """Get list of family members and their current status."""
    user = get_current_user(request)
    db = SessionLocal()
    try:
        friendships = db.query(Friendship).filter(
            or_(Friendship.user1_id == user.id, Friendship.user2_id == user.id),
            Friendship.is_family == True,
        ).all()

        from .database import SafetyAlert
        result = []
        for f in friendships:
            fid = f.user2_id if f.user1_id == user.id else f.user1_id
            friend = db.query(User).filter(User.id == fid).first()
            if friend:
                latest = db.query(SafetyAlert).filter(SafetyAlert.user_id == fid).order_by(
                    SafetyAlert.last_updated.desc()).first()
                result.append({
                    "user": friend.to_dict(),
                    "status": latest.status if latest else None,
                    "alert_type": latest.alert_type if latest else None,
                    "latitude": latest.latitude if latest else None,
                    "longitude": latest.longitude if latest else None,
                    "city": latest.city if latest else None,
                    "last_updated": latest.last_updated.isoformat() if latest and latest.last_updated else None,
                })
        return {"success": True, "data": {"family": result}}
    finally:
        db.close()

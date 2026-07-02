# groups.py - Chat group management and messaging API
from datetime import datetime, timezone
from typing import Optional

from fastapi import APIRouter, HTTPException, Request
from pydantic import BaseModel
from sqlalchemy import or_

from .database import SessionLocal, User, ChatGroup, GroupMember, GroupMessage
from .auth import get_current_user
from .status_ws import status_manager

router = APIRouter(prefix="/api/groups", tags=["groups"])


class CreateGroupRequest(BaseModel):
    name: str
    member_ids: list[int] = []

class AddMemberRequest(BaseModel):
    user_id: int

class SendMessageRequest(BaseModel):
    content: str
    msg_type: str = "text"  # text, location, emergency
    latitude: float = 0.0
    longitude: float = 0.0


# ── Group CRUD ──

@router.post("/create")
async def create_group(body: CreateGroupRequest, request: Request):
    user = get_current_user(request)
    db = SessionLocal()
    try:
        group = ChatGroup(name=body.name, creator_id=user.id)
        db.add(group)
        db.flush()

        # Add creator as member
        db.add(GroupMember(group_id=group.id, user_id=user.id))

        # Add other members
        for mid in body.member_ids:
            if mid != user.id:
                existing = db.query(User).filter(User.id == mid).first()
                if existing:
                    db.add(GroupMember(group_id=group.id, user_id=mid))

        db.commit()
        db.refresh(group)

        # Notify members via WebSocket
        members = db.query(GroupMember).filter(GroupMember.group_id == group.id).all()
        for m in members:
            await status_manager.send_to_user(m.user_id, {
                "type": "group_created",
                "group": group.to_dict(),
            })

        return {"success": True, "data": {"group": group.to_dict()}}
    finally:
        db.close()


@router.get("/")
async def list_groups(request: Request):
    user = get_current_user(request)
    db = SessionLocal()
    try:
        memberships = db.query(GroupMember).filter(
            GroupMember.user_id == user.id
        ).all()
        result = []
        for m in memberships:
            group = db.query(ChatGroup).filter(ChatGroup.id == m.group_id).first()
            if group:
                # Get last message preview
                last_msg = db.query(GroupMessage).filter(
                    GroupMessage.group_id == group.id
                ).order_by(GroupMessage.created_at.desc()).first()
                result.append({
                    **group.to_dict(),
                    "member_count": db.query(GroupMember).filter(GroupMember.group_id == group.id).count(),
                    "last_message": last_msg.to_dict() if last_msg else None,
                })
        return {"success": True, "data": {"groups": result}}
    finally:
        db.close()


@router.get("/{group_id}/members")
async def get_members(group_id: int, request: Request):
    user = get_current_user(request)
    db = SessionLocal()
    try:
        # Verify membership
        member = db.query(GroupMember).filter(
            GroupMember.group_id == group_id, GroupMember.user_id == user.id
        ).first()
        if not member:
            raise HTTPException(status_code=403, detail={"success": False, "error": "Not a member"})

        members = db.query(GroupMember).filter(GroupMember.group_id == group_id).all()
        return {"success": True, "data": {"members": [m.to_dict() for m in members]}}
    finally:
        db.close()


@router.post("/{group_id}/add-member")
async def add_member(group_id: int, body: AddMemberRequest, request: Request):
    user = get_current_user(request)
    db = SessionLocal()
    try:
        member = db.query(GroupMember).filter(
            GroupMember.group_id == group_id, GroupMember.user_id == user.id
        ).first()
        if not member:
            raise HTTPException(status_code=403, detail={"success": False, "error": "Not a member"})

        existing = db.query(GroupMember).filter(
            GroupMember.group_id == group_id, GroupMember.user_id == body.user_id
        ).first()
        if existing:
            return {"success": True, "data": {"message": "Already a member"}}

        new_member = GroupMember(group_id=group_id, user_id=body.user_id)
        db.add(new_member)
        db.commit()

        await status_manager.send_to_user(body.user_id, {
            "type": "member_added",
            "group_id": group_id,
            "group_name": db.query(ChatGroup).filter(ChatGroup.id == group_id).first().name,
        })

        return {"success": True, "data": {"message": "Member added"}}
    finally:
        db.close()


@router.post("/{group_id}/remove-member")
async def remove_member(group_id: int, body: AddMemberRequest, request: Request):
    user = get_current_user(request)
    db = SessionLocal()
    try:
        db.query(GroupMember).filter(
            GroupMember.group_id == group_id, GroupMember.user_id == body.user_id
        ).delete()
        db.commit()
        return {"success": True, "data": {"message": "Member removed"}}
    finally:
        db.close()


# ── Messages ──

@router.post("/{group_id}/message")
async def send_message(group_id: int, body: SendMessageRequest, request: Request):
    user = get_current_user(request)
    db = SessionLocal()
    try:
        member = db.query(GroupMember).filter(
            GroupMember.group_id == group_id, GroupMember.user_id == user.id
        ).first()
        if not member:
            raise HTTPException(status_code=403, detail={"success": False, "error": "Not a member"})

        msg = GroupMessage(
            group_id=group_id,
            sender_id=user.id,
            content=body.content,
            msg_type=body.msg_type,
            latitude=body.latitude,
            longitude=body.longitude,
        )
        db.add(msg)
        # Update last_message_at
        group = db.query(ChatGroup).filter(ChatGroup.id == group_id).first()
        if group:
            group.last_message_at = datetime.now(timezone.utc)
        db.commit()
        db.refresh(msg)

        # Push to all group members via WebSocket
        msg_dict = msg.to_dict()
        all_members = db.query(GroupMember).filter(GroupMember.group_id == group_id).all()
        for m in all_members:
            if m.user_id != user.id:  # Don't push to sender
                await status_manager.send_to_user(m.user_id, {
                    "type": "group_message",
                    "message": msg_dict,
                    "group_name": group.name if group else "",
                })

        return {"success": True, "data": {"message": msg_dict}}
    finally:
        db.close()


@router.get("/{group_id}/messages")
async def get_messages(group_id: int, request: Request, limit: int = 50):
    user = get_current_user(request)
    db = SessionLocal()
    try:
        member = db.query(GroupMember).filter(
            GroupMember.group_id == group_id, GroupMember.user_id == user.id
        ).first()
        if not member:
            raise HTTPException(status_code=403, detail={"success": False, "error": "Not a member"})

        msgs = db.query(GroupMessage).filter(
            GroupMessage.group_id == group_id
        ).order_by(GroupMessage.created_at.desc()).limit(limit).all()

        return {"success": True, "data": {"messages": [m.to_dict() for m in reversed(msgs)]}}
    finally:
        db.close()

# database.py - SQLAlchemy models and database setup for social features
import os
import hashlib
import secrets
from datetime import datetime, timezone
from typing import Optional, List

from sqlalchemy import (
    create_engine, Column, Integer, String, Boolean, Float, DateTime,
    ForeignKey, UniqueConstraint, Text, Index
)
from sqlalchemy.orm import DeclarativeBase, relationship, Session, sessionmaker

DATABASE_URL = os.getenv("VISUS_DB_URL", "sqlite:///" + os.path.join(
    os.path.dirname(os.path.abspath(__file__)), "..", "..", "visus_social.db"
))

engine = create_engine(DATABASE_URL, connect_args={"check_same_thread": False}, echo=False)
SessionLocal = sessionmaker(autocommit=False, autoflush=False, bind=engine)


class Base(DeclarativeBase):
    pass


# ── User ──
class User(Base):
    __tablename__ = "users"

    id = Column(Integer, primary_key=True, autoincrement=True)
    username = Column(String(150), unique=True, nullable=False, index=True)
    email = Column(String(254), unique=True, nullable=False, index=True)
    password_hash = Column(String(128), nullable=False)
    first_name = Column(String(150), default="")
    last_name = Column(String(150), default="")
    is_active = Column(Boolean, default=True)
    user_type = Column(String(20), default="blind")  # "blind" or "family"
    created_at = Column(DateTime, default=lambda: datetime.now(timezone.utc))
    last_login = Column(DateTime, nullable=True)

    # relationships
    safety_alerts = relationship("SafetyAlert", back_populates="user", lazy="dynamic")
    sent_requests = relationship("FriendRequest", foreign_keys="FriendRequest.sender_id", back_populates="sender", lazy="dynamic")
    received_requests = relationship("FriendRequest", foreign_keys="FriendRequest.receiver_id", back_populates="receiver", lazy="dynamic")

    def set_password(self, password: str):
        salt = secrets.token_hex(16)
        self.password_hash = salt + ":" + hashlib.sha256((salt + password).encode()).hexdigest()

    def check_password(self, password: str) -> bool:
        if ":" not in self.password_hash:
            return False
        salt, h = self.password_hash.split(":", 1)
        return h == hashlib.sha256((salt + password).encode()).hexdigest()

    def to_dict(self):
        return {
            "id": self.id,
            "username": self.username,
            "email": self.email,
            "first_name": self.first_name,
            "last_name": self.last_name,
            "user_type": self.user_type,
        }


# ── SafetyAlert ──
class SafetyAlert(Base):
    __tablename__ = "safety_alerts"

    id = Column(Integer, primary_key=True, autoincrement=True)
    user_id = Column(Integer, ForeignKey("users.id"), nullable=False, index=True)
    status = Column(Boolean, default=False)  # True=safe, False=not_safe
    alert_type = Column(String(50), default="manual")  # manual, emergency_fall, emergency_obstacle, auto
    latitude = Column(Float, nullable=True, default=0.0)
    longitude = Column(Float, nullable=True, default=0.0)
    city = Column(String(100), nullable=True)
    note = Column(Text, nullable=True)  # extra info for emergency
    last_updated = Column(DateTime, default=lambda: datetime.now(timezone.utc), index=True)
    created_at = Column(DateTime, default=lambda: datetime.now(timezone.utc))

    user = relationship("User", back_populates="safety_alerts")

    def to_dict(self):
        return {
            "id": self.id,
            "user_id": self.user_id,
            "status": self.status,
            "alert_type": self.alert_type,
            "latitude": self.latitude,
            "longitude": self.longitude,
            "city": self.city,
            "note": self.note,
            "last_updated": self.last_updated.isoformat() if self.last_updated else None,
        }


# ── FriendRequest ──
class FriendRequest(Base):
    __tablename__ = "friend_requests"

    id = Column(Integer, primary_key=True, autoincrement=True)
    sender_id = Column(Integer, ForeignKey("users.id"), nullable=False, index=True)
    receiver_id = Column(Integer, ForeignKey("users.id"), nullable=False, index=True)
    is_pending = Column(Boolean, default=True)
    created_at = Column(DateTime, default=lambda: datetime.now(timezone.utc))

    sender = relationship("User", foreign_keys=[sender_id], back_populates="sent_requests")
    receiver = relationship("User", foreign_keys=[receiver_id], back_populates="received_requests")

    def to_dict(self):
        return {
            "id": self.id,
            "sender": self.sender.to_dict() if self.sender else None,
            "receiver_id": self.receiver_id,
            "is_pending": self.is_pending,
            "created_at": self.created_at.isoformat() if self.created_at else None,
        }


# ── Friendship ──
class Friendship(Base):
    __tablename__ = "friendships"
    __table_args__ = (UniqueConstraint("user1_id", "user2_id"),)

    id = Column(Integer, primary_key=True, autoincrement=True)
    user1_id = Column(Integer, ForeignKey("users.id"), nullable=False, index=True)
    user2_id = Column(Integer, ForeignKey("users.id"), nullable=False, index=True)
    is_family = Column(Boolean, default=False)  # True if designated as family
    created_at = Column(DateTime, default=lambda: datetime.now(timezone.utc))

    user1 = relationship("User", foreign_keys=[user1_id])
    user2 = relationship("User", foreign_keys=[user2_id])


# ── FriendMessage (private 1-on-1 chat) ──
class FriendMessage(Base):
    __tablename__ = "friend_messages"

    id = Column(Integer, primary_key=True, autoincrement=True)
    sender_id = Column(Integer, ForeignKey("users.id"), nullable=False, index=True)
    receiver_id = Column(Integer, ForeignKey("users.id"), nullable=False, index=True)
    content = Column(Text, nullable=False)
    msg_type = Column(String(20), default="text")  # text, location, image
    latitude = Column(Float, nullable=True)
    longitude = Column(Float, nullable=True)
    is_read = Column(Boolean, default=False)
    created_at = Column(DateTime, default=lambda: datetime.now(timezone.utc))

    sender = relationship("User", foreign_keys=[sender_id])
    receiver = relationship("User", foreign_keys=[receiver_id])

    def to_dict(self):
        return {
            "id": self.id, "sender_id": self.sender_id, "receiver_id": self.receiver_id,
            "sender": self.sender.to_dict() if self.sender else None,
            "receiver": self.receiver.to_dict() if self.receiver else None,
            "content": self.content, "msg_type": self.msg_type,
            "latitude": self.latitude, "longitude": self.longitude,
            "is_read": self.is_read,
            "created_at": self.created_at.isoformat() if self.created_at else None,
        }


# ── EmergencyEvent (for tracking emergencies) ──
class EmergencyEvent(Base):
    __tablename__ = "emergency_events"

    id = Column(Integer, primary_key=True, autoincrement=True)
    user_id = Column(Integer, ForeignKey("users.id"), nullable=False, index=True)
    event_type = Column(String(50), default="fall")  # fall, obstacle_collision, manual_sos, voice_trigger
    severity = Column(String(20), default="high")  # low, medium, high, critical
    latitude = Column(Float, nullable=True, default=0.0)
    longitude = Column(Float, nullable=True, default=0.0)
    city = Column(String(100), nullable=True)
    description = Column(Text, nullable=True)
    is_resolved = Column(Boolean, default=False)
    created_at = Column(DateTime, default=lambda: datetime.now(timezone.utc))
    resolved_at = Column(DateTime, nullable=True)

    user = relationship("User")

    def to_dict(self):
        return {
            "id": self.id,
            "user_id": self.user_id,
            "event_type": self.event_type,
            "severity": self.severity,
            "latitude": self.latitude,
            "longitude": self.longitude,
            "city": self.city,
            "description": self.description,
            "is_resolved": self.is_resolved,
            "created_at": self.created_at.isoformat() if self.created_at else None,
        }


# ── Chat Groups ──
class ChatGroup(Base):
    __tablename__ = "chat_groups"

    id = Column(Integer, primary_key=True, autoincrement=True)
    name = Column(String(200), nullable=False)
    creator_id = Column(Integer, ForeignKey("users.id"), nullable=False, index=True)
    created_at = Column(DateTime, default=lambda: datetime.now(timezone.utc))
    last_message_at = Column(DateTime, nullable=True)

    creator = relationship("User")
    members = relationship("GroupMember", back_populates="group", lazy="dynamic")
    messages = relationship("GroupMessage", back_populates="group", lazy="dynamic")

    def to_dict(self):
        return {
            "id": self.id,
            "name": self.name,
            "creator_id": self.creator_id,
            "created_at": self.created_at.isoformat() if self.created_at else None,
            "last_message_at": self.last_message_at.isoformat() if self.last_message_at else None,
        }


class GroupMember(Base):
    __tablename__ = "group_members"
    __table_args__ = (UniqueConstraint("group_id", "user_id"),)

    id = Column(Integer, primary_key=True, autoincrement=True)
    group_id = Column(Integer, ForeignKey("chat_groups.id"), nullable=False, index=True)
    user_id = Column(Integer, ForeignKey("users.id"), nullable=False, index=True)
    joined_at = Column(DateTime, default=lambda: datetime.now(timezone.utc))

    group = relationship("ChatGroup", back_populates="members")
    user = relationship("User")

    def to_dict(self):
        return {
            "id": self.id,
            "group_id": self.group_id,
            "user": self.user.to_dict() if self.user else None,
            "joined_at": self.joined_at.isoformat() if self.joined_at else None,
        }


class GroupMessage(Base):
    __tablename__ = "group_messages"

    id = Column(Integer, primary_key=True, autoincrement=True)
    group_id = Column(Integer, ForeignKey("chat_groups.id"), nullable=False, index=True)
    sender_id = Column(Integer, ForeignKey("users.id"), nullable=False, index=True)
    content = Column(Text, nullable=False)
    msg_type = Column(String(20), default="text")  # text, location, emergency
    latitude = Column(Float, nullable=True)
    longitude = Column(Float, nullable=True)
    created_at = Column(DateTime, default=lambda: datetime.now(timezone.utc))

    group = relationship("ChatGroup", back_populates="messages")
    sender = relationship("User")

    def to_dict(self):
        return {
            "id": self.id,
            "group_id": self.group_id,
            "sender": self.sender.to_dict() if self.sender else None,
            "content": self.content,
            "msg_type": self.msg_type,
            "latitude": self.latitude,
            "longitude": self.longitude,
            "created_at": self.created_at.isoformat() if self.created_at else None,
        }


def init_db():
    """Create all tables if they don't exist."""
    Base.metadata.create_all(bind=engine)


def get_db() -> Session:
    """Get a database session."""
    db = SessionLocal()
    try:
        return db
    finally:
        pass

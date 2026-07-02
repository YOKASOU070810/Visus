# auth.py - JWT-based authentication for social features
import os
import jwt
from datetime import datetime, timedelta, timezone
from functools import wraps
from typing import Optional

from fastapi import HTTPException, Request, Depends
from fastapi.security import HTTPBearer, HTTPAuthorizationCredentials

from .database import User, SessionLocal

JWT_SECRET = os.getenv("VISUS_JWT_SECRET", "visus-dev-secret-change-in-production")
JWT_ALGORITHM = "HS256"
JWT_EXPIRY_HOURS = 72

security = HTTPBearer(auto_error=False)


def create_token(user_id: int) -> str:
    """Create a JWT token for a user."""
    payload = {
        "user_id": user_id,
        "exp": datetime.now(timezone.utc) + timedelta(hours=JWT_EXPIRY_HOURS),
        "iat": datetime.now(timezone.utc),
    }
    return jwt.encode(payload, JWT_SECRET, algorithm=JWT_ALGORITHM)


def decode_token(token: str) -> Optional[int]:
    """Decode a JWT token and return user_id, or None if invalid."""
    try:
        payload = jwt.decode(token, JWT_SECRET, algorithms=[JWT_ALGORITHM])
        return payload.get("user_id")
    except (jwt.ExpiredSignatureError, jwt.InvalidTokenError):
        return None


def get_token_from_request(request: Request) -> Optional[str]:
    """Extract JWT token from Authorization header or query param."""
    auth_header = request.headers.get("Authorization", "")
    if auth_header.startswith("Bearer "):
        return auth_header[7:]
    # also check query param for WebSocket connections
    token = request.query_params.get("token")
    if token:
        return token
    return None


def get_current_user(request: Request) -> User:
    """Dependency: extract and validate current user from JWT."""
    token = get_token_from_request(request)
    if not token:
        raise HTTPException(status_code=401, detail="Not authenticated")

    user_id = decode_token(token)
    if not user_id:
        raise HTTPException(status_code=401, detail="Invalid or expired token")

    db = SessionLocal()
    try:
        user = db.query(User).filter(User.id == user_id).first()
        if not user:
            raise HTTPException(status_code=401, detail="User not found")
        return user
    finally:
        db.close()


def get_optional_user(request: Request) -> Optional[User]:
    """Dependency: extract user if token present, otherwise None (no error)."""
    token = get_token_from_request(request)
    if not token:
        return None

    user_id = decode_token(token)
    if not user_id:
        return None

    db = SessionLocal()
    try:
        return db.query(User).filter(User.id == user_id).first()
    finally:
        db.close()

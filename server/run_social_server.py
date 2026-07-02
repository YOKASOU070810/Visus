#!/usr/bin/env python3
"""Standalone social API server for testing friend alert features.
Does NOT require ML models, GPU, or API keys. Only needs: pip install fastapi uvicorn sqlalchemy PyJWT"""

import sys, os
sys.path.insert(0, os.path.join(os.path.dirname(__file__), "src"))

from fastapi import FastAPI, WebSocket, WebSocketDisconnect
from fastapi.middleware.cors import CORSMiddleware
import uvicorn

app = FastAPI(title="Visus Social Server")

# Allow all origins for mobile app access
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# ---- Social API ----
from social.database import init_db
from social.api_routes import router as social_router
from social.groups import router as groups_router
from social.maps import router as maps_router
from social.ai_agent import router as agent_router
from social.messaging import router as messaging_router
from social.status_ws import handle_status_websocket

app.include_router(social_router)
app.include_router(groups_router)
app.include_router(maps_router)
app.include_router(agent_router)
app.include_router(messaging_router)

@app.get("/")
def root():
    return {"service": "Visus Social Server", "status": "running"}

@app.get("/api/health")
def health():
    return "OK"

@app.websocket("/ws/social")
async def ws_social(ws: WebSocket):
    await handle_status_websocket(ws)

@app.on_event("startup")
async def startup():
    init_db()
    print("[SOCIAL] Database initialized", flush=True)
    print("[SOCIAL] Server ready on port 8081", flush=True)
    print("[SOCIAL] API docs: http://localhost:8081/docs", flush=True)

if __name__ == "__main__":
    print("=" * 50)
    print("Visus Social Server (Lightweight)")
    print("=" * 50)
    print("Starting on http://0.0.0.0:8081")
    print("Required: pip install fastapi uvicorn sqlalchemy PyJWT")
    print()
    uvicorn.run(app, host="0.0.0.0", port=8081, log_level="info")

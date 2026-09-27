import os
import sys
import uuid
from pathlib import Path

from dotenv import load_dotenv
from fastapi import FastAPI, HTTPException, Query
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse, HTMLResponse
from livekit import api
from pydantic import BaseModel
import uvicorn

# Load environment variables
BASE_DIR = Path(__file__).resolve().parent
load_dotenv(BASE_DIR / ".env.local")
load_dotenv(BASE_DIR / ".env")

LIVEKIT_URL = os.getenv("LIVEKIT_URL", "").strip()
LIVEKIT_API_KEY = os.getenv("LIVEKIT_API_KEY", "").strip()
LIVEKIT_API_SECRET = os.getenv("LIVEKIT_API_SECRET", "").strip()

app = FastAPI(
    title="LiveKit Agent Token Server",
    description="FastAPI service to generate LiveKit tokens for connecting frontend with agent.py",
    version="1.0.0",
)

# Enable CORS for cross-origin frontend / Android connections
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)


class TokenRequest(BaseModel):
    room_name: str | None = None
    participant_name: str | None = None
    participant_identity: str | None = None
    identity: str | None = None
    agent_name: str | None = "my-agent"


def create_livekit_token(
    room_name: str,
    participant_name: str,
    agent_name: str | None = "my-agent",
) -> str:
    """Generate a signed LiveKit AccessToken JWT with appropriate grants and agent dispatch configuration."""
    if not LIVEKIT_API_KEY or not LIVEKIT_API_SECRET:
        raise HTTPException(
            status_code=500,
            detail="LIVEKIT_API_KEY or LIVEKIT_API_SECRET is not configured in .env",
        )

    grants = api.VideoGrants(
        room_join=True,
        room=room_name,
        can_publish=True,
        can_subscribe=True,
        can_publish_data=True,
    )

    token = (
        api.AccessToken(LIVEKIT_API_KEY, LIVEKIT_API_SECRET)
        .with_identity(participant_name)
        .with_name(participant_name)
        .with_grants(grants)
    )

    # Attach agent dispatch if agent_name is specified (e.g. "my-agent" in agent.py)
    if agent_name:
        room_config = api.RoomConfiguration(
            agents=[api.RoomAgentDispatch(agent_name=agent_name)]
        )
        token = token.with_room_config(room_config)

    return token.to_jwt()


@app.get("/health")
async def health_check():
    """Health and configuration status endpoint"""
    return {
        "status": "healthy",
        "livekit_url_configured": bool(LIVEKIT_URL),
        "livekit_api_key_configured": bool(LIVEKIT_API_KEY),
        "livekit_url": LIVEKIT_URL,
    }


@app.get("/token")
async def get_token_get(
    room_name: str | None = Query(default=None, description="Room name to join"),
    participant_name: str | None = Query(default=None, description="Participant name"),
    participant_identity: str | None = Query(default=None, description="Participant identity"),
    identity: str | None = Query(default=None, description="Participant identity alias"),
    agent_name: str | None = Query(default="my-agent", description="Agent name to dispatch (from agent.py)"),
):
    """Generate token via GET request (easy for browser testing / queries)."""
    room = room_name or f"room_{uuid.uuid4().hex[:8]}"
    participant = participant_identity or participant_name or identity or f"user_{uuid.uuid4().hex[:6]}"

    token_jwt = create_livekit_token(
        room_name=room,
        participant_name=participant,
        agent_name=agent_name,
    )

    return {
        "server_url": LIVEKIT_URL,
        "room_name": room,
        "participant_name": participant,
        "participant_identity": participant,
        "participant_token": token_jwt,
        "token": token_jwt,
        "agent_name": agent_name,
    }


@app.post("/token")
async def get_token_post(req: TokenRequest):
    """Generate token via POST request with JSON body (compatible with Android TokenService)."""
    room = req.room_name or f"room_{uuid.uuid4().hex[:8]}"
    participant = (
        req.participant_identity
        or req.participant_name
        or req.identity
        or f"user_{uuid.uuid4().hex[:6]}"
    )

    token_jwt = create_livekit_token(
        room_name=room,
        participant_name=participant,
        agent_name=req.agent_name,
    )

    return {
        "server_url": LIVEKIT_URL,
        "room_name": room,
        "participant_name": participant,
        "participant_identity": participant,
        "participant_token": token_jwt,
        "token": token_jwt,
        "agent_name": req.agent_name,
    }


@app.post("/", response_model=None)
async def post_root(req: TokenRequest):
    """Fallback in case client posts to root / instead of /token."""
    return await get_token_post(req)


@app.get("/", response_class=HTMLResponse)
async def serve_index():
    """Serve the index.html test frontend."""
    index_file = BASE_DIR / "index.html"
    if not index_file.exists():
        raise HTTPException(status_code=404, detail="index.html not found")
    return FileResponse(index_file)


if __name__ == "__main__":
    port = int(os.getenv("PORT", "8000"))
    host = os.getenv("HOST", "0.0.0.0")
    print(f"Starting LiveKit FastAPI server on http://localhost:{port} ...")
    uvicorn.run("server:app", host=host, port=port, reload=True)

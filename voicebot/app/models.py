from typing import Optional
from pydantic import BaseModel, Field


class TurnRequest(BaseModel):
    session_id: str = Field(..., description="Unique session identifier for this conversation")
    tenant_id: str = Field(..., description="Tenant UUID")
    contact_id: str = Field(..., description="Contact UUID")
    audio_base64: str = Field(..., description="WAV/PCM audio encoded as base64")
    audio_format: str = Field(default="wav", description="Audio format: wav or pcm")
    turn_number: int = Field(default=1, description="Current turn number (1-based)")


class TurnResponse(BaseModel):
    session_id: str
    transcript: str
    intent: str
    confidence: float
    escalate: bool
    escalation_reason: Optional[str] = None
    full_transcript: list[str] = Field(default_factory=list, description="Full conversation history")
    response_text: str = Field(default="", description="Voicebot response to be spoken via TTS")
    continue_conversation: bool = Field(default=False, description="Whether the voicebot wants to record another turn")


class HealthResponse(BaseModel):
    status: str

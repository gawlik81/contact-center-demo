from enum import Enum
from typing import Optional
from pydantic import BaseModel, Field


class AiProvider(str, Enum):
    ANTHROPIC = "ANTHROPIC"
    OPENAI = "OPENAI"
    AZURE_OPENAI = "AZURE_OPENAI"
    OPENROUTER = "OPENROUTER"


class SummarizeRequest(BaseModel):
    channel: str = Field(..., description="Contact channel: PHONE | EMAIL | SOCIAL_MEDIA")
    content: str = Field(..., description="Content to summarize")
    provider: AiProvider = Field(..., description="AI provider to use")
    api_key: str = Field(..., description="API key for the provider")
    model_name: str = Field(..., description="Model identifier (e.g. claude-3-5-sonnet-20241022)")
    azure_endpoint: str | None = Field(default=None, description="Azure OpenAI endpoint URL (required for AZURE_OPENAI)")
    deployment_name: str | None = Field(default=None, description="Azure deployment name (falls back to model_name)")
    prompt_template: str | None = Field(default=None, description="Custom system prompt; None = use default")


class SummarizeResponse(BaseModel):
    summary: str = Field(..., description="Generated summary text")
    model_used: str = Field(..., description="Model identifier that produced the summary")
    tokens_used: int = Field(..., description="Total tokens consumed (input + output)")


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

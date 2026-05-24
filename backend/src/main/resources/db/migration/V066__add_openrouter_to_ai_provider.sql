-- =============================================================================
-- V066__add_openrouter_to_ai_provider.sql
-- Dodanie wartości OPENROUTER do ENUM ai_provider.
-- OpenRouter udostępnia OpenAI-compatible API (https://openrouter.ai/api/v1).
-- =============================================================================

ALTER TYPE ai_provider ADD VALUE IF NOT EXISTS 'OPENROUTER';

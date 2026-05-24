"""
AI summarization module for BE-091.
Supports Anthropic, OpenAI, Azure OpenAI and OpenRouter providers.
Each provider client is instantiated per-request (api_key comes from the caller).
"""
import asyncio
import logging

from app.models import AiProvider, SummarizeRequest, SummarizeResponse

logger = logging.getLogger(__name__)

DEFAULT_PROMPT = (
    "You are an expert contact center assistant. Summarize the following {channel} contact "
    "in 3-5 sentences. Focus on: customer issue, resolution outcome, and any follow-up actions. "
    "Reply in the same language as the content."
)

TIMEOUT_SECONDS = 25


async def summarize(request: SummarizeRequest) -> SummarizeResponse:
    """Dispatches summarization to the appropriate AI provider."""
    prompt = request.prompt_template or DEFAULT_PROMPT.format(channel=request.channel)

    if request.provider == AiProvider.ANTHROPIC:
        return await _summarize_anthropic(request, prompt)
    elif request.provider == AiProvider.OPENAI:
        return await _summarize_openai(request, prompt)
    elif request.provider == AiProvider.AZURE_OPENAI:
        return await _summarize_azure(request, prompt)
    elif request.provider == AiProvider.OPENROUTER:
        return await _summarize_openrouter(request, prompt)
    else:
        raise ValueError(f"Unknown provider: {request.provider}")


async def _summarize_anthropic(request: SummarizeRequest, system_prompt: str) -> SummarizeResponse:
    import anthropic

    client = anthropic.AsyncAnthropic(api_key=request.api_key)
    try:
        response = await asyncio.wait_for(
            client.messages.create(
                model=request.model_name,
                max_tokens=1024,
                system=system_prompt,
                messages=[{"role": "user", "content": request.content}],
            ),
            timeout=TIMEOUT_SECONDS,
        )
        summary = response.content[0].text
        tokens_used = response.usage.input_tokens + response.usage.output_tokens
        return SummarizeResponse(
            summary=summary,
            model_used=request.model_name,
            tokens_used=tokens_used,
        )
    except asyncio.TimeoutError:
        raise TimeoutError("Anthropic API timeout")
    except Exception as exc:
        logger.error("[Summarize] Anthropic error: %s", type(exc).__name__)
        raise RuntimeError(f"AI provider error: {type(exc).__name__}") from exc


async def _summarize_openai(request: SummarizeRequest, system_prompt: str) -> SummarizeResponse:
    from openai import AsyncOpenAI

    client = AsyncOpenAI(api_key=request.api_key)
    try:
        response = await asyncio.wait_for(
            client.chat.completions.create(
                model=request.model_name,
                messages=[
                    {"role": "system", "content": system_prompt},
                    {"role": "user", "content": request.content},
                ],
                max_tokens=1024,
            ),
            timeout=TIMEOUT_SECONDS,
        )
        summary = response.choices[0].message.content or ""
        tokens_used = response.usage.total_tokens if response.usage else 0
        return SummarizeResponse(
            summary=summary,
            model_used=request.model_name,
            tokens_used=tokens_used,
        )
    except asyncio.TimeoutError:
        raise TimeoutError("OpenAI API timeout")
    except Exception as exc:
        logger.error("[Summarize] OpenAI error: %s", type(exc).__name__)
        raise RuntimeError(f"AI provider error: {type(exc).__name__}") from exc


async def _summarize_azure(request: SummarizeRequest, system_prompt: str) -> SummarizeResponse:
    from openai import AsyncAzureOpenAI

    client = AsyncAzureOpenAI(
        api_key=request.api_key,
        azure_endpoint=request.azure_endpoint,
        api_version="2024-02-01",
    )
    deployment = request.deployment_name or request.model_name
    try:
        response = await asyncio.wait_for(
            client.chat.completions.create(
                model=deployment,
                messages=[
                    {"role": "system", "content": system_prompt},
                    {"role": "user", "content": request.content},
                ],
                max_tokens=1024,
            ),
            timeout=TIMEOUT_SECONDS,
        )
        summary = response.choices[0].message.content or ""
        tokens_used = response.usage.total_tokens if response.usage else 0
        return SummarizeResponse(
            summary=summary,
            model_used=deployment,
            tokens_used=tokens_used,
        )
    except asyncio.TimeoutError:
        raise TimeoutError("Azure OpenAI API timeout")
    except Exception as exc:
        logger.error("[Summarize] Azure OpenAI error: %s", type(exc).__name__)
        raise RuntimeError(f"AI provider error: {type(exc).__name__}") from exc


async def _summarize_openrouter(request: SummarizeRequest, system_prompt: str) -> SummarizeResponse:
    from openai import AsyncOpenAI

    client = AsyncOpenAI(
        api_key=request.api_key,
        base_url="https://openrouter.ai/api/v1",
    )
    try:
        response = await asyncio.wait_for(
            client.chat.completions.create(
                model=request.model_name,
                messages=[
                    {"role": "system", "content": system_prompt},
                    {"role": "user", "content": request.content},
                ],
                max_tokens=1024,
            ),
            timeout=TIMEOUT_SECONDS,
        )
        summary = response.choices[0].message.content or ""
        tokens_used = response.usage.total_tokens if response.usage else 0
        return SummarizeResponse(
            summary=summary,
            model_used=request.model_name,
            tokens_used=tokens_used,
        )
    except asyncio.TimeoutError:
        raise TimeoutError("OpenRouter API timeout")
    except Exception as exc:
        logger.error("[Summarize] OpenRouter error: %s", type(exc).__name__)
        raise RuntimeError(f"AI provider error: {type(exc).__name__}") from exc

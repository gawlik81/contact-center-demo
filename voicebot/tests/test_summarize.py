"""
Testy jednostkowe modułu app.summarize (BE-091).
Wszystkie wywołania SDK są mockowane — brak połączeń z zewnętrznymi API.
Uruchomienie: pytest tests/test_summarize.py -v
"""
import pytest
from unittest.mock import AsyncMock, MagicMock, patch

from app.models import AiProvider, SummarizeRequest
from app.summarize import summarize


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def make_request(provider: AiProvider, **kwargs) -> SummarizeRequest:
    return SummarizeRequest(
        channel="PHONE",
        content="Customer called about invoice problem. Agent resolved by issuing credit.",
        provider=provider,
        api_key="test-key",
        model_name="test-model",
        **kwargs,
    )


# ---------------------------------------------------------------------------
# Anthropic
# ---------------------------------------------------------------------------

@pytest.mark.asyncio
async def test_summarize_anthropic_happy_path():
    mock_response = MagicMock()
    mock_response.content = [MagicMock(text="Summary text")]
    mock_response.usage.input_tokens = 100
    mock_response.usage.output_tokens = 50

    with patch("app.summarize.anthropic.AsyncAnthropic") as mock_cls:
        mock_client = AsyncMock()
        mock_cls.return_value = mock_client
        mock_client.messages.create = AsyncMock(return_value=mock_response)

        result = await summarize(make_request(AiProvider.ANTHROPIC))

    assert result.summary == "Summary text"
    assert result.tokens_used == 150
    assert result.model_used == "test-model"


@pytest.mark.asyncio
async def test_summarize_anthropic_http_error():
    with patch("app.summarize.anthropic.AsyncAnthropic") as mock_cls:
        mock_client = AsyncMock()
        mock_cls.return_value = mock_client
        mock_client.messages.create = AsyncMock(side_effect=Exception("API Error"))

        with pytest.raises(RuntimeError, match="AI provider error"):
            await summarize(make_request(AiProvider.ANTHROPIC))


@pytest.mark.asyncio
async def test_summarize_anthropic_custom_prompt():
    mock_response = MagicMock()
    mock_response.content = [MagicMock(text="Custom summary")]
    mock_response.usage.input_tokens = 80
    mock_response.usage.output_tokens = 30

    with patch("app.summarize.anthropic.AsyncAnthropic") as mock_cls:
        mock_client = AsyncMock()
        mock_cls.return_value = mock_client
        mock_client.messages.create = AsyncMock(return_value=mock_response)

        result = await summarize(
            make_request(AiProvider.ANTHROPIC, prompt_template="Custom system prompt")
        )

        # Verify that the custom prompt was passed as system parameter
        call_kwargs = mock_client.messages.create.call_args.kwargs
        assert call_kwargs["system"] == "Custom system prompt"

    assert result.summary == "Custom summary"
    assert result.tokens_used == 110


# ---------------------------------------------------------------------------
# OpenAI
# ---------------------------------------------------------------------------

@pytest.mark.asyncio
async def test_summarize_openai_happy_path():
    mock_response = MagicMock()
    mock_response.choices = [MagicMock(message=MagicMock(content="OpenAI summary"))]
    mock_response.usage.total_tokens = 200

    with patch("app.summarize.AsyncOpenAI") as mock_cls:
        mock_client = AsyncMock()
        mock_cls.return_value = mock_client
        mock_client.chat.completions.create = AsyncMock(return_value=mock_response)

        result = await summarize(make_request(AiProvider.OPENAI))

    assert result.summary == "OpenAI summary"
    assert result.tokens_used == 200
    assert result.model_used == "test-model"


@pytest.mark.asyncio
async def test_summarize_openai_null_usage_defaults_to_zero():
    mock_response = MagicMock()
    mock_response.choices = [MagicMock(message=MagicMock(content="Summary"))]
    mock_response.usage = None

    with patch("app.summarize.AsyncOpenAI") as mock_cls:
        mock_client = AsyncMock()
        mock_cls.return_value = mock_client
        mock_client.chat.completions.create = AsyncMock(return_value=mock_response)

        result = await summarize(make_request(AiProvider.OPENAI))

    assert result.tokens_used == 0


@pytest.mark.asyncio
async def test_summarize_openai_null_content_returns_empty_string():
    mock_response = MagicMock()
    mock_response.choices = [MagicMock(message=MagicMock(content=None))]
    mock_response.usage.total_tokens = 50

    with patch("app.summarize.AsyncOpenAI") as mock_cls:
        mock_client = AsyncMock()
        mock_cls.return_value = mock_client
        mock_client.chat.completions.create = AsyncMock(return_value=mock_response)

        result = await summarize(make_request(AiProvider.OPENAI))

    assert result.summary == ""


# ---------------------------------------------------------------------------
# Azure OpenAI
# ---------------------------------------------------------------------------

@pytest.mark.asyncio
async def test_summarize_azure_happy_path():
    mock_response = MagicMock()
    mock_response.choices = [MagicMock(message=MagicMock(content="Azure summary"))]
    mock_response.usage.total_tokens = 180

    with patch("app.summarize.AsyncAzureOpenAI") as mock_cls:
        mock_client = AsyncMock()
        mock_cls.return_value = mock_client
        mock_client.chat.completions.create = AsyncMock(return_value=mock_response)

        result = await summarize(
            make_request(
                AiProvider.AZURE_OPENAI,
                azure_endpoint="https://myresource.openai.azure.com/",
                deployment_name="gpt4-deployment",
            )
        )

    assert result.summary == "Azure summary"
    assert result.model_used == "gpt4-deployment"
    assert result.tokens_used == 180


@pytest.mark.asyncio
async def test_summarize_azure_falls_back_to_model_name_when_no_deployment():
    """deployment_name=None → model_used powinien być model_name."""
    mock_response = MagicMock()
    mock_response.choices = [MagicMock(message=MagicMock(content="Azure fallback summary"))]
    mock_response.usage.total_tokens = 90

    with patch("app.summarize.AsyncAzureOpenAI") as mock_cls:
        mock_client = AsyncMock()
        mock_cls.return_value = mock_client
        mock_client.chat.completions.create = AsyncMock(return_value=mock_response)

        result = await summarize(
            make_request(
                AiProvider.AZURE_OPENAI,
                azure_endpoint="https://myresource.openai.azure.com/",
                # deployment_name intentionally omitted
            )
        )

    assert result.model_used == "test-model"


@pytest.mark.asyncio
async def test_summarize_azure_error_raises_runtime_error():
    with patch("app.summarize.AsyncAzureOpenAI") as mock_cls:
        mock_client = AsyncMock()
        mock_cls.return_value = mock_client
        mock_client.chat.completions.create = AsyncMock(side_effect=Exception("Azure API Error"))

        with pytest.raises(RuntimeError, match="AI provider error"):
            await summarize(
                make_request(
                    AiProvider.AZURE_OPENAI,
                    azure_endpoint="https://myresource.openai.azure.com/",
                )
            )


# ---------------------------------------------------------------------------
# Default prompt interpolation
# ---------------------------------------------------------------------------

@pytest.mark.asyncio
async def test_default_prompt_includes_channel():
    """Domyślny prompt zawiera nazwę kanału z requestu."""
    mock_response = MagicMock()
    mock_response.content = [MagicMock(text="Email summary")]
    mock_response.usage.input_tokens = 60
    mock_response.usage.output_tokens = 20

    with patch("app.summarize.anthropic.AsyncAnthropic") as mock_cls:
        mock_client = AsyncMock()
        mock_cls.return_value = mock_client
        mock_client.messages.create = AsyncMock(return_value=mock_response)

        await summarize(
            SummarizeRequest(
                channel="EMAIL",
                content="Email content here.",
                provider=AiProvider.ANTHROPIC,
                api_key="key",
                model_name="model",
            )
        )

        call_kwargs = mock_client.messages.create.call_args.kwargs
        assert "EMAIL" in call_kwargs["system"]

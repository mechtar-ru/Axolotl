package com.agent.orchestrator.llm;

import com.agent.orchestrator.repository.CustomLlmEndpointRepository;
import com.agent.orchestrator.service.SettingsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.agent.orchestrator.llm.LlmResponse.textOnly;

@ExtendWith(MockitoExtension.class)
class LlmServiceTest {

    @Mock LlmProvider ollama;
    @Mock LlmProvider openai;
    @Mock LlmProvider anthropic;
    @Mock LlmProvider deepseek;
    @Mock CustomLlmEndpointRepository customEndpointRepository;
    @Mock SettingsService settingsService;

    LlmService llmService;

    @BeforeEach
    void setUp() {
        when(ollama.getName()).thenReturn("ollama");
        when(openai.getName()).thenReturn("openai");
        when(anthropic.getName()).thenReturn("anthropic");
        when(deepseek.getName()).thenReturn("deepseek");
        lenient().when(customEndpointRepository.findAll()).thenReturn(List.of());

        llmService = new LlmService(List.of(ollama, openai, anthropic, deepseek), customEndpointRepository, settingsService);
    }

    @Test
    void resolveProvider_ollama() {
        when(ollama.chat(any(), any(), any(), any(), any())).thenReturn(textOnly("ok"));
        llmService.chat("ollama", null, "test", null);
        verify(ollama).chat(eq("ollama"), isNull(), eq("test"), isNull(), any());
    }

    @Test
    void resolveProvider_local() {
        when(ollama.chat(any(), any(), any(), any(), any())).thenReturn(textOnly("ok"));
        llmService.chat("local", null, "test", null);
        verify(ollama).chat(eq("local"), isNull(), eq("test"), isNull(), any());
    }

    @Test
    void resolveProvider_gptPrefix() {
        when(openai.chat(any(), any(), any(), any(), any())).thenReturn(textOnly("ok"));
        llmService.chat("gpt-4o", null, "test", null);
        verify(openai).chat(eq("gpt-4o"), isNull(), eq("test"), isNull(), any());
    }

    @Test
    void resolveProvider_claudePrefix() {
        when(anthropic.chat(any(), any(), any(), any(), any())).thenReturn(textOnly("ok"));
        llmService.chat("claude-sonnet-4-20250514", "system", "test", null);
        verify(anthropic).chat(eq("claude-sonnet-4-20250514"), eq("system"), eq("test"), isNull(), any());
    }

    @Test
    void resolveProvider_deepseekPrefix() {
        when(deepseek.chat(any(), any(), any(), any(), any())).thenReturn(textOnly("ok"));
        llmService.chat("deepseek-chat", null, "test", null);
        verify(deepseek).chat(eq("deepseek-chat"), isNull(), eq("test"), isNull(), any());
    }

    @Test
    void resolveProvider_ollamaModelNames() {
        when(ollama.chat(any(), any(), any(), any(), any())).thenReturn(textOnly("ok"));
        llmService.chat("gemma4:e2b", null, "test", null);
        verify(ollama).chat(eq("gemma4:e2b"), isNull(), eq("test"), isNull(), any());
    }

    @Test
    void resolveProvider_null_defaultsToOllama() {
        when(ollama.chat(any(), any(), any(), any(), any())).thenReturn(textOnly("ok"));
        llmService.chat(null, null, "test", null);
        verify(ollama).chat(isNull(), isNull(), eq("test"), isNull(), any());
    }

    @Test
    void resolveProvider_blank_defaultsToOllama() {
        when(ollama.chat(any(), any(), any(), any(), any())).thenReturn(textOnly("ok"));
        llmService.chat("  ", null, "test", null);
        verify(ollama).chat(eq("  "), isNull(), eq("test"), isNull(), any());
    }

    @Test
    void isProviderAvailable_true() {
        when(ollama.isAvailable()).thenReturn(true);
        assertTrue(llmService.isProviderAvailable("ollama"));
    }

    @Test
    void isProviderAvailable_false() {
        when(openai.isAvailable()).thenReturn(false);
        assertFalse(llmService.isProviderAvailable("openai"));
    }

    @Test
    void chat_unknownProvider_defaultsToOllama() {
        // "nonexistent" doesn't match any prefix → falls back to ollama
        when(ollama.chat(any(), any(), any(), any(), any())).thenReturn(textOnly("fallback response"));
        String result = llmService.chat("nonexistent", null, "test", null).text();
        assertEquals("fallback response", result);
        verify(ollama).chat(eq("nonexistent"), isNull(), eq("test"), isNull(), any());
    }

    @Test
    void getProvidersInfo_returnsAll() {
        when(ollama.isAvailable()).thenReturn(true);
        when(ollama.getBaseUrl()).thenReturn("http://localhost:11434");
        when(ollama.listModels()).thenReturn(List.of("gemma4:e2b"));

        var info = llmService.getProvidersInfo();
        assertEquals(4, info.size());

        var ollamaInfo = info.stream().filter(i -> "ollama".equals(i.get("name"))).findFirst().orElseThrow();
        assertEquals(true, ollamaInfo.get("available"));
        assertEquals("http://localhost:11434", ollamaInfo.get("baseUrl"));
    }

    @Test
    void streamingChat_delegatesToProvider() {
        when(ollama.streamingChat(any(), any(), any(), any(), any(), any())).thenReturn(textOnly("streamed"));
        StringBuilder tokens = new StringBuilder();
        String result = llmService.streamingChat("ollama", null, "test", null, tokens::append).text();
        assertEquals("streamed", result);
        verify(ollama).streamingChat(eq("ollama"), isNull(), eq("test"), isNull(), any(), any());
    }

    @Test
    void listModels_returnsModels() {
        when(ollama.listModels()).thenReturn(List.of("model1", "model2"));
        List<String> models = llmService.listModels("ollama");
        assertEquals(List.of("model1", "model2"), models);
    }

    @Test
    void listModels_unknownProvider_returnsEmpty() {
        List<String> models = llmService.listModels("unknown");
        assertTrue(models.isEmpty());
    }

    // ─── Model fallback chain tests ───

    @Test
    void chat_fallsBackToAlternateModelWhenPrimaryFails() {
        when(ollama.chat(any(), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("429 Too Many Requests"))
                .thenReturn(textOnly("fallback-ok"));
        Map<String, Object> config = Map.of("fallbackModels", List.of("ollama/mistral"));
        String result = llmService.chat("ollama/llama", null, "test", config).text();
        assertEquals("fallback-ok", result);
        verify(ollama, times(2)).chat(any(), any(), any(), any(), any());
    }

    @Test
    void chat_throwsWhenAllModelsExhausted() {
        when(ollama.chat(any(), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("503 Service Unavailable"));
        Map<String, Object> config = Map.of("fallbackModels", List.of("ollama/mistral"));
        assertThrows(RuntimeException.class,
                () -> llmService.chat("ollama/llama", null, "test", config));
        verify(ollama, times(2)).chat(any(), any(), any(), any(), any());
    }

    @Test
    void streamingChat_fallsBackOnTransientError() {
        when(ollama.streamingChat(any(), any(), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("rate limit exceeded"))
                .thenReturn(textOnly("stream-fallback-ok"));
        Map<String, Object> config = Map.of("fallbackModels", List.of("ollama/mistral"));
        String result = llmService.streamingChat("ollama/llama", null, "test", config, t -> {}).text();
        assertEquals("stream-fallback-ok", result);
    }

    @Test
    void buildModelChain_noFallbacks_returnsPrimaryOnly() {
        List<String> chain = llmService.buildModelChain("gpt-4o", null);
        assertEquals(List.of("gpt-4o"), chain);
    }

    @Test
    void buildModelChain_withFallbacks_returnsFullChain() {
        Map<String, Object> config = Map.of("fallbackModels", List.of("deepseek-chat", "claude-sonnet-4"));
        List<String> chain = llmService.buildModelChain("gpt-4o", config);
        assertEquals(List.of("gpt-4o", "deepseek-chat", "claude-sonnet-4"), chain);
    }

    @Test
    void buildModelChain_deduplicatesFallbacks() {
        Map<String, Object> config = Map.of("fallbackModels", List.of("gpt-4o", "deepseek-chat"));
        List<String> chain = llmService.buildModelChain("gpt-4o", config);
        assertEquals(List.of("gpt-4o", "deepseek-chat"), chain);
    }
}

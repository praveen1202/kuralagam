package com.tamilvoice.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Holds Groq API configuration loaded from application.properties.
 * The API key is injected from the environment variable GROQ_API_KEY
 * — never hardcode secrets in source code.
 */
@Configuration
public class GroqConfig {

    private static final Logger log = LoggerFactory.getLogger(GroqConfig.class);

    @Value("${groq.api.key}")
    private String apiKey;

    @Value("${groq.api.url:https://api.groq.com/openai/v1/chat/completions}")
    private String apiUrl;

    @Value("${groq.audio.url:https://api.groq.com/openai/v1/audio/transcriptions}")
    private String audioUrl;

    @Value("${groq.models.url:https://api.groq.com/openai/v1/models}")
    private String modelsUrl;

    @Value("${groq.model:llama-3.3-70b-versatile}")
    private String model;

    @Value("${groq.audio.model:whisper-large-v3-turbo}")
    private String audioModel;

    @Value("${groq.max-tokens:1000}")
    private int maxTokens;

    /**
     * Groq periodically retires chat models, which turns a hardcoded
     * groq.model into a 404 at request time. On startup, check the configured
     * model against Groq's live catalogue and fall back to another available
     * chat model rather than failing every chat request.
     *
     * Groq's /models list mixes in TTS, guard, and other non-chat models with
     * no ordering guarantee, so picking the "first" entry is unsafe — it once
     * landed on a text-to-speech model, which produced garbled chat replies.
     * Instead, fall back through a fixed preference list of known chat models.
     */
    private static final List<String> PREFERRED_CHAT_MODELS = List.of(
            "llama-3.3-70b-versatile",
            "llama-3.1-70b-versatile",
            "openai/gpt-oss-120b",
            "openai/gpt-oss-20b",
            "llama-3.1-8b-instant"
    );

    @PostConstruct
    void resolveModel() {
        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build();

        Request request = new Request.Builder()
                .url(modelsUrl)
                .header("Authorization", "Bearer " + apiKey)
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                log.warn("Could not fetch Groq model list ({}); keeping configured model '{}'",
                        response.code(), model);
                return;
            }

            JsonNode root = new ObjectMapper().readTree(response.body().string());
            Set<String> availableIds = new HashSet<>();
            for (JsonNode entry : root.path("data")) {
                String id = entry.path("id").asText();
                if (!id.isBlank()) {
                    availableIds.add(id);
                }
            }

            if (availableIds.contains(model)) {
                log.info("Groq model '{}' confirmed available", model);
                return;
            }

            String fallback = PREFERRED_CHAT_MODELS.stream()
                    .filter(availableIds::contains)
                    .findFirst()
                    .orElse(null);

            if (fallback != null) {
                log.warn("Configured Groq model '{}' is not available; falling back to '{}'. " +
                        "Set groq.model to make this permanent.", model, fallback);
                model = fallback;
            } else {
                log.error("Configured Groq model '{}' is unavailable and none of the known fallback " +
                        "chat models {} are available either. Chat requests will fail until groq.model " +
                        "is set to a model this key can access.", model, PREFERRED_CHAT_MODELS);
            }
        } catch (Exception e) {
            log.warn("Error fetching Groq model list; keeping configured model '{}': {}", model, e.getMessage());
        }
    }

    public String getApiKey() { return apiKey; }
    public String getApiUrl() { return apiUrl; }
    public String getAudioUrl() { return audioUrl; }
    public String getModel() { return model; }
    public String getAudioModel() { return audioModel; }
    public int getMaxTokens() { return maxTokens; }
}

package com.tamilvoice.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/**
 * Holds Groq API configuration loaded from application.properties.
 * The API key is injected from the environment variable GROQ_API_KEY
 * — never hardcode secrets in source code.
 */
@Configuration
public class GroqConfig {

    @Value("${groq.api.key}")
    private String apiKey;

    @Value("${groq.api.url:https://api.groq.com/openai/v1/chat/completions}")
    private String apiUrl;

    @Value("${groq.audio.url:https://api.groq.com/openai/v1/audio/transcriptions}")
    private String audioUrl;

    @Value("${groq.model:llama-3.3-70b-versatile}")
    private String model;

    @Value("${groq.audio.model:whisper-large-v3-turbo}")
    private String audioModel;

    @Value("${groq.max-tokens:1000}")
    private int maxTokens;

    public String getApiKey() { return apiKey; }
    public String getApiUrl() { return apiUrl; }
    public String getAudioUrl() { return audioUrl; }
    public String getModel() { return model; }
    public String getAudioModel() { return audioModel; }
    public int getMaxTokens() { return maxTokens; }
}

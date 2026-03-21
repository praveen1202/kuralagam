package com.tamilvoice.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tamilvoice.config.GroqConfig;
import com.tamilvoice.model.ChatRequest;
import com.tamilvoice.model.ChatResponse;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * GroqService handles all communication with the Groq API.
 *
 * Responsibilities:
 *  1. Build a structured JSON request payload
 *  2. Set the correct auth headers (Authorization: Bearer)
 *  3. Parse the JSON response and extract the text content
 *  4. Split the response into main reply + English translation
 */
@Service
public class GroqService {

    private static final Logger log = LoggerFactory.getLogger(GroqService.class);

    private final GroqConfig config;
    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;

    public GroqService(GroqConfig config) {
        this.config = config;
        this.objectMapper = new ObjectMapper();

        // Build OkHttpClient with sensible timeouts
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(45, TimeUnit.SECONDS)   // AI responses can take time
                .writeTimeout(15, TimeUnit.SECONDS)
                .build();
    }

    /**
     * Send a user message to Groq and return the assistant's response.
     */
    public ChatResponse chat(ChatRequest chatRequest) {
        try {
            String requestBody = buildRequestPayload(chatRequest);
            log.debug("Sending request to Groq API for language: {}", chatRequest.getLanguage());

            Request httpRequest = new Request.Builder()
                    .url(config.getApiUrl())
                    .post(RequestBody.create(requestBody, MediaType.get("application/json; charset=utf-8")))
                    .header("Authorization", "Bearer " + config.getApiKey())
                    .header("Content-Type", "application/json")
                    .build();

            try (Response response = httpClient.newCall(httpRequest).execute()) {
                String responseBody = response.body() != null ? response.body().string() : "";

                if (!response.isSuccessful()) {
                    log.error("Groq API error {}: {}", response.code(), responseBody);
                    return ChatResponse.error("API error " + response.code() + ". Check your API key.");
                }

                return parseResponse(responseBody);
            }

        } catch (IOException e) {
            log.error("Network error calling Groq API", e);
            return ChatResponse.error("Network error: " + e.getMessage());
        } catch (Exception e) {
            log.error("Unexpected error in GroqService", e);
            return ChatResponse.error("Unexpected error: " + e.getMessage());
        }
    }

    /**
     * Convert audio data to text using Groq Whisper.
     */
    public String transcribe(byte[] audioData, String languageCode) {
        log.info("Transcribing audio — size: {} bytes, lang context: {}", audioData.length, languageCode);
        
        // Extract language prefix (e.g. ta-IN -> ta)
        String lang = (languageCode != null && languageCode.contains("-")) 
                      ? languageCode.split("-")[0] 
                      : "ta";

        RequestBody fileBody = RequestBody.create(audioData, MediaType.parse("audio/webm"));
        
        RequestBody requestBody = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("model", config.getAudioModel())
                .addFormDataPart("language", lang)
                .addFormDataPart("file", "speech.webm", fileBody)
                .build();

        Request request = new Request.Builder()
                .url(config.getAudioUrl())
                .header("Authorization", "Bearer " + config.getApiKey())
                .post(requestBody)
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            String responseBody = response.body() != null ? response.body().string() : "";

            if (!response.isSuccessful()) {
                log.error("Groq Whisper error {}: {}", response.code(), responseBody);
                throw new RuntimeException("Transcription failed: " + responseBody);
            }

            JsonNode root = objectMapper.readTree(responseBody);
            String transcript = root.path("text").asText().trim();
            log.info("Transcription successful: {}", transcript);
            return transcript;

        } catch (Exception e) {
            log.error("Error during Whisper transcription", e);
            throw new RuntimeException("Transcription error: " + e.getMessage());
        }
    }

    /**
     * Build the JSON payload for the Groq completions endpoint.
     */
    private String buildRequestPayload(ChatRequest req) throws Exception {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("model", config.getModel());
        payload.put("max_tokens", config.getMaxTokens());

        ArrayNode messages = objectMapper.createArrayNode();
        
        ObjectNode systemMsg = objectMapper.createObjectNode();
        systemMsg.put("role", "system");
        systemMsg.put("content", buildSystemPrompt(req));
        messages.add(systemMsg);

        ObjectNode userMsg = objectMapper.createObjectNode();
        userMsg.put("role", "user");
        userMsg.put("content", req.getMessage());
        messages.add(userMsg);

        payload.set("messages", messages);
        return objectMapper.writeValueAsString(payload);
    }

    /**
     * Build a culturally aware system prompt for the chosen Indic language.
     */
    private String buildSystemPrompt(ChatRequest req) {
        return String.format("""
            You are "Kuralargam" (குரலகம்), a warm and helpful voice assistant for speakers of %s.
            The user communicates via voice — their speech has been converted to text.

            STRICT RESPONSE FORMAT:
            Line 1: Your response written entirely in %s native script (e.g. தமிழ் for Tamil).
            Line 2: English translation in parentheses, e.g. (I will help you with that.)

            RULES:
            - Keep responses SHORT — 1 to 3 sentences maximum. This is a voice interface.
            - Be warm, respectful, and conversational.
            - Use culturally appropriate language for %s speakers.
            - NEVER add extra lines, disclaimers, or preambles — just the two lines above.
            - If the user speaks in English, still respond in %s script first, then translate.
            """,
            req.getLanguageName(), req.getLanguageName(),
            req.getLanguageName(), req.getLanguageName()
        );
    }

    /**
     * Parse the Groq API JSON response and extract text content.
     * Splits into main reply and optional English translation.
     */
    private ChatResponse parseResponse(String responseBody) throws Exception {
        JsonNode root = objectMapper.readTree(responseBody);
        JsonNode choices = root.path("choices");

        if (choices.isEmpty()) {
            return ChatResponse.error("Empty response from Groq API.");
        }

        String text = choices.get(0).path("message").path("content").asText().trim();
        String[] lines = text.split("\\n");

        String mainReply = lines[0].trim();
        String translation = null;

        // Look for an English translation line in parentheses
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.startsWith("(") && line.endsWith(")")) {
                translation = line;
                break;
            }
        }

        log.debug("Groq response parsed — main: {}, translation: {}", mainReply, translation);
        return ChatResponse.ok(mainReply, translation);
    }
}

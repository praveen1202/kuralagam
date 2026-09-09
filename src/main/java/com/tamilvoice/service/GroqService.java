package com.tamilvoice.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tamilvoice.config.GroqConfig;
import com.tamilvoice.model.ChatRequest;
import com.tamilvoice.model.ChatResponse;
import com.tamilvoice.model.ConversationMessage;
import com.tamilvoice.model.TranscriptionResult;
import okhttp3.*;
import okio.BufferedSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Handles all communication with the Groq API — building requests (including
 * conversation history and scheme grounding context), and parsing/streaming
 * the response back.
 */
@Service
public class GroqService {

    private static final Logger log = LoggerFactory.getLogger(GroqService.class);

    /**
     * Prefix the model puts on line 1 when it declines an out-of-domain question.
     * Stripped before the text is shown, stored in history, or captured — it only
     * tells the UI to render the reply as a scope decline.
     */
    static final String OFF_TOPIC_MARKER = "[OFF_TOPIC]";

    private final GroqConfig config;
    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final ConversationService conversationService;
    private final SchemeKnowledgeService schemeKnowledgeService;
    private final DataCaptureService dataCaptureService;

    public GroqService(GroqConfig config,
                        ConversationService conversationService,
                        SchemeKnowledgeService schemeKnowledgeService,
                        DataCaptureService dataCaptureService) {
        this.config = config;
        this.objectMapper = new ObjectMapper();
        this.conversationService = conversationService;
        this.schemeKnowledgeService = schemeKnowledgeService;
        this.dataCaptureService = dataCaptureService;

        // Build OkHttpClient with sensible timeouts
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(45, TimeUnit.SECONDS)   // AI responses can take time
                .writeTimeout(15, TimeUnit.SECONDS)
                .build();
    }

    public ChatResponse chat(ChatRequest chatRequest) {
        try {
            PreparedRequest prepared = buildRequestPayload(chatRequest, false);
            String requestBody = prepared.payload();
            log.debug("Sending request to Groq API for language: {}", chatRequest.getLanguage());

            Request httpRequest = new Request.Builder()
                    .url(config.getApiUrl())
                    .post(RequestBody.create(requestBody, MediaType.get("application/json; charset=utf-8")))
                    .header("Authorization", "Bearer " + config.getApiKey())
                    .header("Content-Type", "application/json")
                    .build();

            long startTime = System.currentTimeMillis();
            try (Response response = httpClient.newCall(httpRequest).execute()) {
                long duration = System.currentTimeMillis() - startTime;
                String responseBody = response.body() != null ? response.body().string() : "";

                if (!response.isSuccessful()) {
                    log.error("Groq API error {}: {} (Took {}ms)", response.code(), responseBody, duration);
                    return ChatResponse.error("API error " + response.code() + ". Check your API key.");
                }

                log.info("Groq LLM Response received in {}ms. Raw Length: {}", duration, responseBody.length());
                String rawText = extractRawReply(responseBody);
                if (rawText == null) {
                    return ChatResponse.error("Empty response from Groq API.");
                }

                ChatResponse chatResponse = splitReply(rawText);
                chatResponse.setConversationId(chatRequest.getConversationId());
                conversationService.appendTurn(chatRequest.getConversationId(), chatRequest.getMessage(),
                        stripMarker(rawText));
                return chatResponse;
            }

        } catch (IOException e) {
            log.error("Network error calling Groq API", e);
            return ChatResponse.error("Network error: " + e.getMessage());
        } catch (Exception e) {
            log.error("Unexpected error in GroqService", e);
            return ChatResponse.error("Unexpected error: " + e.getMessage());
        }
    }

    // Runs blocking I/O — caller must invoke this from a background executor,
    // never the Tomcat request thread.
    public void chatStream(ChatRequest chatRequest, SseEmitter emitter) {
        long startTime = System.currentTimeMillis();
        StringBuilder fullReply = new StringBuilder();

        try {
            PreparedRequest prepared = buildRequestPayload(chatRequest, true);
            String requestBody = prepared.payload();

            // Source pills are known before the first token — send them up front so the
            // UI can attach them to the bubble the moment the answer finishes.
            emitter.send(SseEmitter.event().name("meta").data(
                    objectMapper.writeValueAsString(
                            objectMapper.createObjectNode()
                                    .set("sources", objectMapper.valueToTree(prepared.match().sources())))));

            Request httpRequest = new Request.Builder()
                    .url(config.getApiUrl())
                    .post(RequestBody.create(requestBody, MediaType.get("application/json; charset=utf-8")))
                    .header("Authorization", "Bearer " + config.getApiKey())
                    .header("Content-Type", "application/json")
                    .build();

            try (Response response = httpClient.newCall(httpRequest).execute()) {
                if (!response.isSuccessful() || response.body() == null) {
                    String errBody = response.body() != null ? response.body().string() : "";
                    log.error("Groq streaming API error {}: {}", response.code(), errBody);
                    emitter.completeWithError(new IOException("API error " + response.code()));
                    return;
                }

                BufferedSource source = response.body().source();
                String line;
                while ((line = source.readUtf8Line()) != null) {
                    if (!line.startsWith("data: ")) continue;
                    String data = line.substring(6).trim();
                    if (data.equals("[DONE]")) break;
                    if (data.isEmpty()) continue;

                    JsonNode node = objectMapper.readTree(data);
                    JsonNode deltaContent = node.path("choices").path(0).path("delta").path("content");
                    if (!deltaContent.isMissingNode() && !deltaContent.isNull()) {
                        String token = deltaContent.asText();
                        fullReply.append(token);
                        emitter.send(SseEmitter.event().name("token").data(token));
                    }
                }
            }

            long duration = System.currentTimeMillis() - startTime;
            String rawText = fullReply.toString();

            if (!rawText.isBlank()) {
                ChatResponse parsed = splitReply(rawText);
                conversationService.appendTurn(chatRequest.getConversationId(), chatRequest.getMessage(),
                        stripMarker(rawText));
                String inputType = chatRequest.getSttDurationMs() > 0 ? "voice" : "text";
                dataCaptureService.capture(
                        chatRequest.getLanguage(),
                        chatRequest.getMessage(),
                        chatRequest.getSttDurationMs(),
                        parsed.getReply(),
                        parsed.getTranslation(),
                        duration,
                        inputType,
                        chatRequest.getConversationId()
                );
            }

            emitter.complete();

        } catch (IOException e) {
            log.error("Network error during Groq streaming", e);
            emitter.completeWithError(e);
        } catch (Exception e) {
            log.error("Unexpected error during Groq streaming", e);
            emitter.completeWithError(e);
        }
    }

    /**
     * Convert audio data to text using Groq Whisper.
     * Returns a TranscriptionResult containing both the text and the STT latency,
     * so the controller can pass timing data to DataCaptureService and the frontend.
     */
    public TranscriptionResult transcribe(byte[] audioData, String languageCode) {
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

        long startTime = System.currentTimeMillis();
        try (Response response = httpClient.newCall(request).execute()) {
            long duration = System.currentTimeMillis() - startTime;
            String responseBody = response.body() != null ? response.body().string() : "";

            if (!response.isSuccessful()) {
                log.error("Groq Whisper error {}: {} (Took {}ms)", response.code(), responseBody, duration);
                throw new RuntimeException("Transcription failed: " + responseBody);
            }

            JsonNode root = objectMapper.readTree(responseBody);
            String transcript = root.path("text").asText().trim();
            log.info("Transcription successful in {}ms: \"{}\"", duration, transcript);
            return new TranscriptionResult(transcript, duration);

        } catch (Exception e) {
            log.error("Error during Whisper transcription", e);
            throw new RuntimeException("Transcription error: " + e.getMessage());
        }
    }

    /** A built Groq request together with the knowledge-base match that grounded it. */
    private record PreparedRequest(String payload, SchemeKnowledgeService.MatchResult match) {}

    private PreparedRequest buildRequestPayload(ChatRequest req, boolean stream) throws Exception {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("model", config.getModel());
        payload.put("max_tokens", config.getMaxTokens());
        if (stream) {
            payload.put("stream", true);
        }

        ArrayNode messages = objectMapper.createArrayNode();

        String systemPrompt = buildSystemPrompt(req);

        // RAG-lite retrieval. A low score means the knowledge base has nothing
        // confident for this question — log it so the gap feeds the review queue
        // that drives what we add to schemes.json next.
        SchemeKnowledgeService.MatchResult match = schemeKnowledgeService.findRelevant(req.getMessage());
        if (!match.context().isBlank()) {
            systemPrompt = systemPrompt + "\n" + match.context();
        }
        if (match.isGap()) {
            dataCaptureService.captureKnowledgeGap(
                    req.getLanguage(),
                    req.getMessage(),
                    match.topScore(),
                    match.matchedIds(),
                    req.getConversationId()
            );
        }

        ObjectNode systemMsg = objectMapper.createObjectNode();
        systemMsg.put("role", "system");
        systemMsg.put("content", systemPrompt);
        messages.add(systemMsg);

        List<ConversationMessage> history = conversationService.getHistory(req.getConversationId());
        for (ConversationMessage m : history) {
            ObjectNode historyMsg = objectMapper.createObjectNode();
            historyMsg.put("role", m.getRole());
            historyMsg.put("content", m.getContent());
            messages.add(historyMsg);
        }

        ObjectNode userMsg = objectMapper.createObjectNode();
        userMsg.put("role", "user");
        userMsg.put("content", req.getMessage());
        messages.add(userMsg);

        payload.set("messages", messages);
        return new PreparedRequest(objectMapper.writeValueAsString(payload), match);
    }

    /**
     * Build the domain-restricted system prompt for the TN Health & Education assistant.
     *
     * Domain scope:
     *   - Tamil Nadu government Health schemes (CMCHIS, Dr. Kalaignar Insurance,
     *     free medicines, government hospital services, maternal health programmes, etc.)
     *   - Tamil Nadu government Education schemes (scholarships, free uniforms,
     *     mid-day meals, Pudhumai Penn, government college admissions, etc.)
     *
     * Out-of-domain questions receive a polite decline in the target language.
     * This guardrail is intentionally strict so evaluation data reflects only
     * relevant interactions, making quality measurement meaningful.
     */
    private String buildSystemPrompt(ChatRequest req) {
        String replyLanguage = replyLanguageOf(req);
        String glossLanguage = glossLanguageFor(replyLanguage);

        return String.format("""
            You are "Kuralagam" (குரலகம்), an assistant that helps people in Tamil Nadu
            understand government Health and Education schemes.

            The user is chatting with you in text or voice — either way, their input arrives as text.

            YOUR DOMAIN — answer ONLY questions about:
            1. Tamil Nadu government HEALTH schemes:
               Examples: CMCHIS (Chief Minister's Comprehensive Health Insurance Scheme),
               Dr. Kalaignar Insurance Scheme, free medicine programme, government hospital
               services, maternal and child health programmes, free dialysis, cancer screening.
            2. Tamil Nadu government EDUCATION schemes:
               Examples: scholarships (SC/ST, OBC, minority), free school uniforms and books,
               mid-day meal scheme, Pudhumai Penn scheme for girls, government college fee
               concessions, Amma Unavagam, library and digital access programmes.

            STRICT RESPONSE FORMAT:
            Line 1: Your answer written entirely in %s.
            Line 2: A %s rendering of line 1, wrapped in parentheses.
                    The reader may never open line 2 — so line 1 must stand on its own,
                    and line 2 must carry the same facts, not a summary of them.

            RULES:
            - Answer in the SAME language the user wrote in. The user wrote in %s, so line 1
              is in %s — regardless of what language earlier turns used.
            - Keep responses SHORT — 2 to 3 sentences maximum.
            - Be warm, respectful, and use simple language accessible to rural users.
            - Always name the specific scheme when relevant.
            - If the question is outside TN Health or Education schemes, begin your reply with
              the marker %s followed by a space, then politely decline in %s and explain you
              can only help with TN government health and education schemes. Do NOT answer
              general knowledge, politics, entertainment, or other topics. The marker goes on
              line 1 only — never on line 2.
            - NEVER add extra lines, disclaimers, or preambles — just the two lines above.
            - Output plain text only — normal spaces, no HTML tags or HTML entities
              (e.g. write a real space, never "&nbsp;").
            """,
            replyLanguage,
            glossLanguage,
            replyLanguage,
            replyLanguage,
            OFF_TOPIC_MARKER,
            replyLanguage
        );
    }

    /**
     * The language the answer itself is written in. The frontend detects the script the
     * user actually typed and sends it as languageName, so the assistant mirrors the
     * user's language rather than the interface setting.
     */
    private String replyLanguageOf(ChatRequest req) {
        String name = req.getLanguageName();
        return (name == null || name.isBlank()) ? "Tamil" : name.trim();
    }

    /** The parenthesised second line is always the other side of the pair. */
    private String glossLanguageFor(String replyLanguage) {
        return replyLanguage.equalsIgnoreCase("English") ? "Tamil" : "English";
    }

    /** Removes the scope marker so it never reaches history, capture, or the reader. */
    private static String stripMarker(String rawText) {
        String trimmed = rawText.stripLeading();
        return trimmed.startsWith(OFF_TOPIC_MARKER)
                ? trimmed.substring(OFF_TOPIC_MARKER.length()).stripLeading()
                : rawText;
    }

    private String extractRawReply(String responseBody) throws Exception {
        JsonNode root = objectMapper.readTree(responseBody);
        JsonNode choices = root.path("choices");

        if (choices.isEmpty()) {
            return null;
        }

        return choices.get(0).path("message").path("content").asText().trim();
    }

    // Splits per the system prompt's "native line, then (translation) line" contract.
    private ChatResponse splitReply(String text) {
        String[] lines = text.split("\\n");

        String mainReply = lines[0].trim();
        boolean outOfScope = mainReply.startsWith(OFF_TOPIC_MARKER);
        if (outOfScope) {
            mainReply = mainReply.substring(OFF_TOPIC_MARKER.length()).stripLeading();
        }
        String translation = null;

        // Look for an English translation line in parentheses
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.startsWith("(") && line.endsWith(")")) {
                translation = line;
                break;
            }
        }

        log.debug("Groq response parsed — main: {}, translation: {}, outOfScope: {}",
                mainReply, translation, outOfScope);
        ChatResponse parsed = ChatResponse.ok(mainReply, translation);
        parsed.setOutOfScope(outOfScope);
        return parsed;
    }
}

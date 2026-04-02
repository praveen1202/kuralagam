package com.tamilvoice.controller;

import com.tamilvoice.model.ChatRequest;
import com.tamilvoice.model.ChatResponse;
import com.tamilvoice.model.TranscriptionResult;
import com.tamilvoice.service.DataCaptureService;
import com.tamilvoice.service.GroqService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * VoiceAssistantController
 *
 * Routes:
 * GET  /              → Serves the main HTML page (Thymeleaf template)
 * POST /api/transcribe → Receives audio blob, returns { text, sttDurationMs }
 * POST /api/chat      → Accepts ChatRequest, returns ChatResponse; writes to DataCaptureService
 * POST /api/telemetry → Logs frontend events (TTS, errors, etc.)
 * GET  /health        → Simple health check
 */
@Controller
public class VoiceAssistantController {

    private static final Logger log = LoggerFactory.getLogger(VoiceAssistantController.class);

    private final GroqService groqService;
    private final DataCaptureService dataCaptureService;

    public VoiceAssistantController(GroqService groqService, DataCaptureService dataCaptureService) {
        this.groqService = groqService;
        this.dataCaptureService = dataCaptureService;
    }

    /** Serve the main web app page */
    @GetMapping("/")
    public String index() {
        return "index";
    }

    /**
     * Transcription endpoint.
     * Receives an audio blob, sends it to Groq Whisper, returns:
     *   { success: true, text: "...", sttDurationMs: 412 }
     * The sttDurationMs is forwarded by the frontend in the subsequent /api/chat request.
     */
    @PostMapping("/api/transcribe")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> transcribe(
            @RequestParam("audio") org.springframework.web.multipart.MultipartFile audio,
            @RequestParam("language") String language) {

        log.info("Transcription request: size={} bytes, lang={}", audio.getSize(), language);

        if (audio.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("success", false, "error", "Audio file is empty"));
        }

        try {
            TranscriptionResult result = groqService.transcribe(audio.getBytes(), language);
            log.info("STT complete — \"{}\" in {}ms", result.getText(), result.getDurationMs());
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "text", result.getText(),
                    "sttDurationMs", result.getDurationMs()
            ));
        } catch (Exception e) {
            log.error("Transcription failed", e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("success", false, "error", e.getMessage()));
        }
    }

    /**
     * Main chat endpoint.
     * Accepts: { message, language, languageName, sttDurationMs }
     * Returns: { success, reply, translation }
     *
     * Writes a complete interaction record to DataCaptureService after each
     * successful LLM response (STT + LLM latencies + transcription + reply).
     */
    @PostMapping("/api/chat")
    @ResponseBody
    public ResponseEntity<ChatResponse> chat(@Valid @RequestBody ChatRequest request) {
        log.info("Chat request — lang: {}, inputType: {}, msgLen: {}",
                request.getLanguage(),
                request.getSttDurationMs() > 0 ? "voice" : "text",
                request.getMessage().length());

        long llmStart = System.currentTimeMillis();
        ChatResponse response = groqService.chat(request);
        long llmDurationMs = System.currentTimeMillis() - llmStart;

        if (response.isSuccess()) {
            String inputType = request.getSttDurationMs() > 0 ? "voice" : "text";
            dataCaptureService.capture(
                    request.getLanguage(),
                    request.getMessage(),
                    request.getSttDurationMs(),
                    response.getReply(),
                    response.getTranslation(),
                    llmDurationMs,
                    inputType
            );
            log.info("LLM complete in {}ms (total voice latency: {}ms)",
                    llmDurationMs,
                    request.getSttDurationMs() + llmDurationMs);
            return ResponseEntity.ok(response);
        } else {
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /** Telemetry endpoint — logs frontend events (TTS start/end/error, voice missing, etc.) */
    @PostMapping("/api/telemetry")
    @ResponseBody
    public ResponseEntity<Void> telemetry(@RequestBody Map<String, Object> data) {
        log.info("FRONTEND TELEMETRY: {}", data);
        return ResponseEntity.ok().build();
    }

    /** Health check — used by Render, Railway, fly.io, etc. */
    @GetMapping("/health")
    @ResponseBody
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("OK");
    }
}

package com.tamilvoice.controller;

import com.tamilvoice.model.ChatRequest;
import com.tamilvoice.model.ChatResponse;
import com.tamilvoice.service.GroqService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

/**
 * VoiceAssistantController
 *
 * Routes:
 * GET / → Serves the main HTML page (Thymeleaf template)
 * POST /api/chat → Accepts a JSON ChatRequest, returns a JSON ChatResponse
 * GET /health → Simple health check endpoint (useful for hosting platforms)
 */
@Controller
public class VoiceAssistantController {

    private static final Logger log = LoggerFactory.getLogger(VoiceAssistantController.class);

    private final GroqService groqService;

    public VoiceAssistantController(GroqService groqService) {
        this.groqService = groqService;
    }

    /** Serve the main web app page */
    @GetMapping("/")
    public String index() {
        return "index"; // Resolves to src/main/resources/templates/index.html
    }

    /**
     * Main chat endpoint.
     * Accepts: { "message": "...", "language": "ta-IN", "languageName": "Tamil" }
     * Returns: { "success": true, "reply": "...", "translation": "(...)" }
     */
    @PostMapping("/api/chat")
    @ResponseBody
    public ResponseEntity<ChatResponse> chat(@Valid @RequestBody ChatRequest request) {
        log.info("Chat request received — language: {}, message length: {}",
                request.getLanguage(), request.getMessage().length());

        ChatResponse response = groqService.chat(request);

        if (response.isSuccess()) {
            return ResponseEntity.ok(response);
        } else {
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /** Health check — used by Render, Railway, fly.io, AWS etc. */
    @GetMapping("/health")
    @ResponseBody
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("OK");
    }

    /**
     * Transcription endpoint.
     * Receives an audio blob, sends it to Groq Whisper, returns text.
     */
    @PostMapping("/api/transcribe")
    @ResponseBody
    public ResponseEntity<java.util.Map<String, Object>> transcribe(
            @RequestParam("audio") org.springframework.web.multipart.MultipartFile audio,
            @RequestParam("language") String language) {

        log.info("Transcription request: size={} bytes, lang={}", audio.getSize(), language);

        if (audio.isEmpty()) {
            return ResponseEntity.badRequest().body(java.util.Map.of("success", false, "error", "Audio file is empty"));
        }

        try {
            String text = groqService.transcribe(audio.getBytes(), language);
            return ResponseEntity.ok(java.util.Map.of("success", true, "text", text));
        } catch (Exception e) {
            log.error("Transcription failed", e);
            return ResponseEntity.internalServerError()
                    .body(java.util.Map.of("success", false, "error", e.getMessage()));
        }
    }
}

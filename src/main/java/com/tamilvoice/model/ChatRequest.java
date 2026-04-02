package com.tamilvoice.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Represents a chat request from the frontend.
 * Contains the user's spoken text and target language.
 */
public class ChatRequest {

    @NotBlank(message = "Message cannot be empty")
    @Size(max = 2000, message = "Message too long")
    private String message;

    private String language = "ta-IN";       // BCP-47 language code
    private String languageName = "Tamil";   // Human-readable name

    // Phase 1: passed by frontend when input came from voice (Whisper STT).
    // 0 means the user typed the message manually.
    private long sttDurationMs = 0;

    public ChatRequest() {}

    public ChatRequest(String message, String language, String languageName) {
        this.message = message;
        this.language = language;
        this.languageName = languageName;
    }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public String getLanguage() { return language; }
    public void setLanguage(String language) { this.language = language; }

    public String getLanguageName() { return languageName; }
    public void setLanguageName(String languageName) { this.languageName = languageName; }

    public long getSttDurationMs() { return sttDurationMs; }
    public void setSttDurationMs(long sttDurationMs) { this.sttDurationMs = sttDurationMs; }
}

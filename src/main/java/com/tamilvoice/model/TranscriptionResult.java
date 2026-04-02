package com.tamilvoice.model;

/**
 * Holds the result of a Whisper STT call: the transcribed text and how long it took.
 * Used to carry timing data from GroqService back through the controller to the
 * frontend, enabling end-to-end latency breakdown (STT ms + LLM ms).
 */
public class TranscriptionResult {

    private final String text;
    private final long durationMs;

    public TranscriptionResult(String text, long durationMs) {
        this.text = text;
        this.durationMs = durationMs;
    }

    public String getText() { return text; }
    public long getDurationMs() { return durationMs; }
}

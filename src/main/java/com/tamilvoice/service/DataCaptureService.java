package com.tamilvoice.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;

/**
 * DataCaptureService — Phase 1 Observability
 *
 * Appends each completed voice interaction to a JSON Lines file
 * (one JSON object per line). This gives us a structured dataset
 * to evaluate STT accuracy, LLM response quality, and latency.
 *
 * File location is configured via `data.capture.path` in application.properties.
 * Each record contains:
 *   - timestamp        : ISO-8601 UTC
 *   - language         : BCP-47 code (e.g. ta-IN)
 *   - transcription    : raw Whisper output (what the user said)
 *   - sttDurationMs    : Whisper processing time
 *   - llmReply         : assistant response in target language
 *   - llmTranslation   : English gloss (may be null)
 *   - llmDurationMs    : Groq LLM round-trip time
 *   - inputType        : "voice" | "text" (so we can filter typed inputs)
 */
@Service
public class DataCaptureService {

    private static final Logger log = LoggerFactory.getLogger(DataCaptureService.class);

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Path captureFile;

    public DataCaptureService(
            @Value("${data.capture.path:data/interactions.jsonl}") String path) {
        this.captureFile = Paths.get(path);
        try {
            Files.createDirectories(captureFile.getParent());
            log.info("DataCaptureService ready — writing to {}", captureFile.toAbsolutePath());
        } catch (IOException e) {
            log.warn("Could not create data capture directory: {}", e.getMessage());
        }
    }

    /**
     * Append one interaction record to the JSONL file.
     * Thread-safe via synchronized block — acceptable for low-volume usage.
     *
     * @param language      BCP-47 language code
     * @param transcription raw STT output (user's spoken text)
     * @param sttDurationMs Whisper latency in milliseconds (0 if typed)
     * @param llmReply      assistant reply in target language
     * @param llmTranslation English gloss, may be null
     * @param llmDurationMs Groq LLM round-trip latency
     * @param inputType     "voice" or "text"
     */
    public void capture(String language, String transcription, long sttDurationMs,
                        String llmReply, String llmTranslation, long llmDurationMs,
                        String inputType) {
        try {
            ObjectNode record = objectMapper.createObjectNode();
            record.put("timestamp", Instant.now().toString());
            record.put("language", language);
            record.put("transcription", transcription);
            record.put("sttDurationMs", sttDurationMs);
            record.put("llmReply", llmReply);
            if (llmTranslation != null) {
                record.put("llmTranslation", llmTranslation);
            }
            record.put("llmDurationMs", llmDurationMs);
            record.put("inputType", inputType);

            String line = objectMapper.writeValueAsString(record);

            synchronized (this) {
                try (PrintWriter pw = new PrintWriter(new FileWriter(captureFile.toFile(), true))) {
                    pw.println(line);
                }
            }
            log.debug("Captured {} interaction — STT: {}ms, LLM: {}ms", inputType, sttDurationMs, llmDurationMs);

        } catch (IOException e) {
            log.warn("Failed to write interaction to capture file: {}", e.getMessage());
        }
    }
}

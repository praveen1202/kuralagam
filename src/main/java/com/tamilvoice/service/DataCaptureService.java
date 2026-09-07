package com.tamilvoice.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
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
import java.util.List;

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
 *
 * A second file captures *knowledge gaps* — queries where the scheme
 * knowledge base scored low or found nothing. That file is the review
 * queue for deciding what to add to schemes.json next: rather than
 * guessing whether our coverage is complete, we let real user questions
 * tell us where it isn't.
 */
@Service
public class DataCaptureService {

    private static final Logger log = LoggerFactory.getLogger(DataCaptureService.class);

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Path captureFile;
    private final Path gapFile;

    public DataCaptureService(
            @Value("${data.capture.path:data/interactions.jsonl}") String path,
            @Value("${data.capture.gap-path:data/knowledge-gaps.jsonl}") String gapPath) {
        this.captureFile = Paths.get(path);
        this.gapFile = Paths.get(gapPath);
        ensureDirectory(captureFile);
        ensureDirectory(gapFile);
        log.info("DataCaptureService ready — interactions: {}, knowledge gaps: {}",
                captureFile.toAbsolutePath(), gapFile.toAbsolutePath());
    }

    private void ensureDirectory(Path file) {
        Path parent = file.getParent();
        if (parent == null) return;
        try {
            Files.createDirectories(parent);
        } catch (IOException e) {
            log.warn("Could not create data capture directory {}: {}", parent, e.getMessage());
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
     * @param conversationId client-generated conversation identifier, may be null
     */
    public void capture(String language, String transcription, long sttDurationMs,
                        String llmReply, String llmTranslation, long llmDurationMs,
                        String inputType, String conversationId) {
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
            if (conversationId != null) {
                record.put("conversationId", conversationId);
            }

            append(captureFile, record);
            log.debug("Captured {} interaction — STT: {}ms, LLM: {}ms", inputType, sttDurationMs, llmDurationMs);

        } catch (IOException e) {
            log.warn("Failed to write interaction to capture file: {}", e.getMessage());
        }
    }

    /**
     * Record a query the scheme knowledge base could not confidently answer.
     *
     * These accumulate into a gap list: periodically read the file, cluster the
     * queries, and add the recurring ones to schemes.json (verifying facts against
     * the portals listed in scheme-sources.json first). This is the coverage signal
     * — it reflects what people actually ask, not what we assumed they would.
     *
     * @param language       BCP-47 language code
     * @param query          the user's question as transcribed/typed
     * @param topScore       best keyword score achieved (0 = no scheme matched at all)
     * @param matchedIds     ids of any weakly-matched schemes, may be empty
     * @param conversationId client-generated conversation identifier, may be null
     */
    public void captureKnowledgeGap(String language, String query, int topScore,
                                     List<String> matchedIds, String conversationId) {
        try {
            ObjectNode record = objectMapper.createObjectNode();
            record.put("timestamp", Instant.now().toString());
            record.put("language", language);
            record.put("query", query);
            record.put("topScore", topScore);
            ArrayNode ids = record.putArray("weakMatches");
            if (matchedIds != null) {
                matchedIds.forEach(ids::add);
            }
            if (conversationId != null) {
                record.put("conversationId", conversationId);
            }

            append(gapFile, record);
            log.info("Knowledge gap logged (score {}): \"{}\"", topScore,
                    query.length() > 80 ? query.substring(0, 80) + "..." : query);

        } catch (IOException e) {
            log.warn("Failed to write knowledge gap to capture file: {}", e.getMessage());
        }
    }

    /** Append one JSON record as a line. Synchronized — acceptable for low-volume usage. */
    private void append(Path file, ObjectNode record) throws IOException {
        String line = objectMapper.writeValueAsString(record);
        synchronized (this) {
            try (PrintWriter pw = new PrintWriter(new FileWriter(file.toFile(), true))) {
                pw.println(line);
            }
        }
    }
}

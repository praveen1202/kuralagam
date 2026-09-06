package com.tamilvoice.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.CollectionType;
import com.tamilvoice.model.ConversationMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Multi-turn conversation history, Redis-backed (key "conv:{conversationId}")
 * so it survives app restarts. Idle conversations expire via Redis TTL,
 * refreshed on every write.
 */
@Service
public class ConversationService {

    private static final Logger log = LoggerFactory.getLogger(ConversationService.class);
    private static final String KEY_PREFIX = "conv:";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final int maxTurns;
    private final Duration ttl;

    public ConversationService(
            StringRedisTemplate redisTemplate,
            @Value("${conversation.max-turns:10}") int maxTurns,
            @Value("${conversation.ttl-minutes:30}") long ttlMinutes) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = new ObjectMapper();
        this.maxTurns = maxTurns;
        this.ttl = Duration.ofMinutes(ttlMinutes);
    }

    public List<ConversationMessage> getHistory(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) return List.of();

        String json = redisTemplate.opsForValue().get(KEY_PREFIX + conversationId);
        if (json == null) return List.of();

        List<ConversationMessage> messages = readMessages(json);
        return Collections.unmodifiableList(messages);
    }

    public void appendTurn(String conversationId, String userText, String assistantText) {
        if (conversationId == null || conversationId.isBlank()) return;

        String key = KEY_PREFIX + conversationId;
        String existingJson = redisTemplate.opsForValue().get(key);
        List<ConversationMessage> messages = existingJson != null
                ? new ArrayList<>(readMessages(existingJson))
                : new ArrayList<>();

        messages.add(new ConversationMessage("user", userText));
        messages.add(new ConversationMessage("assistant", assistantText));

        while (messages.size() > maxTurns * 2) {
            messages.remove(0);
            messages.remove(0);
        }

        try {
            String newJson = objectMapper.writeValueAsString(messages);
            redisTemplate.opsForValue().set(key, newJson, ttl);
        } catch (Exception e) {
            log.warn("Failed to persist conversation {}: {}", conversationId, e.getMessage());
        }
    }

    public void clear(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) return;
        redisTemplate.delete(KEY_PREFIX + conversationId);
        log.debug("Cleared conversation: {}", conversationId);
    }

    // SCAN instead of KEYS — avoids blocking Redis on large keyspaces.
    public int activeCount() {
        int count = 0;
        try (var cursor = redisTemplate.getConnectionFactory().getConnection()
                .scan(ScanOptions.scanOptions().match(KEY_PREFIX + "*").count(200).build())) {
            while (cursor.hasNext()) {
                cursor.next();
                count++;
            }
        }
        return count;
    }

    private List<ConversationMessage> readMessages(String json) {
        try {
            CollectionType type = objectMapper.getTypeFactory()
                    .constructCollectionType(List.class, ConversationMessage.class);
            return objectMapper.readValue(json, type);
        } catch (Exception e) {
            log.warn("Failed to deserialize conversation history: {}", e.getMessage());
            return new ArrayList<>();
        }
    }
}

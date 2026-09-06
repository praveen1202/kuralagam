package com.tamilvoice.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A single message in a multi-turn conversation.
 * Role is "user" or "assistant", matching the Groq/OpenAI messages API.
 */
public class ConversationMessage {

    private final String role;    // "user" or "assistant"
    private final String content;

    @JsonCreator
    public ConversationMessage(
            @JsonProperty("role") String role,
            @JsonProperty("content") String content) {
        this.role    = role;
        this.content = content;
    }

    public String getRole()    { return role; }
    public String getContent() { return content; }
}

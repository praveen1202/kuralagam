package com.tamilvoice.model;

/**
 * Represents the AI response sent back to the frontend.
 */
public class ChatResponse {

    private boolean success;
    private String reply;        // Main response (in the target language)
    private String translation;  // English translation in parentheses (optional)
    private String error;        // Error message if success=false
    private String conversationId;

    // True when the model declined because the question fell outside the
    // TN Health/Education domain. The UI renders these declines differently
    // from ordinary answers.
    private boolean outOfScope;

    // Static factory methods for clean construction
    public static ChatResponse ok(String reply, String translation) {
        ChatResponse r = new ChatResponse();
        r.success = true;
        r.reply = reply;
        r.translation = translation;
        return r;
    }

    public static ChatResponse error(String message) {
        ChatResponse r = new ChatResponse();
        r.success = false;
        r.error = message;
        return r;
    }

    public boolean isSuccess() { return success; }
    public void setSuccess(boolean success) { this.success = success; }

    public String getReply() { return reply; }
    public void setReply(String reply) { this.reply = reply; }

    public String getTranslation() { return translation; }
    public void setTranslation(String translation) { this.translation = translation; }

    public String getError() { return error; }
    public void setError(String error) { this.error = error; }

    public String getConversationId() { return conversationId; }
    public void setConversationId(String conversationId) { this.conversationId = conversationId; }

    public boolean isOutOfScope() { return outOfScope; }
    public void setOutOfScope(boolean outOfScope) { this.outOfScope = outOfScope; }
}

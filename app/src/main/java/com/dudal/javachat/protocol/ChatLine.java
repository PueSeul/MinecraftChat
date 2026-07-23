package com.dudal.javachat.protocol;

public final class ChatLine {
    public enum Kind { PLAYER, SYSTEM, PRESENCE, LOCAL_ERROR }

    private final long timestamp;
    private final Kind kind;
    private final String sender;
    private final String message;
    private final String formattedSender;
    private final String formattedMessage;

    public ChatLine(long timestamp, Kind kind, String sender, String message) {
        this.timestamp = timestamp;
        this.kind = kind;
        this.formattedSender = sender;
        this.formattedMessage = message;
        this.sender = LegacyText.strip(sender);
        this.message = LegacyText.strip(message);
    }

    public long getTimestamp() { return timestamp; }
    public Kind getKind() { return kind; }
    public String getSender() { return sender; }
    public String getMessage() { return message; }
    public String getFormattedSender() { return formattedSender; }
    public String getFormattedMessage() { return formattedMessage; }
}

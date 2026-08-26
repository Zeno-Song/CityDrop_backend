package com.citydrop.backend.chat;

public class ChatUnavailableException extends RuntimeException {
    public ChatUnavailableException(String message) {
        super(message);
    }
}

package com.citydrop.backend.models.requests;

import java.util.List;

public record ChatRequest(String message, List<ChatMessage> history) {}

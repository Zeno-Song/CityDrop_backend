package com.citydrop.backend.chat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Thin wrapper around OpenAI's Chat Completions API. No SDK dependency --
 * it's one endpoint, so a plain HttpClient + the ObjectMapper Spring Boot
 * already provides is simpler than pulling in a whole client library.
 */
@Component
class OpenAiClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiClient.class);
    private static final URI ENDPOINT = URI.create("https://api.openai.com/v1/chat/completions");
    private static final URI TRANSCRIPTION_ENDPOINT = URI.create("https://api.openai.com/v1/audio/transcriptions");
    private static final URI SPEECH_ENDPOINT = URI.create("https://api.openai.com/v1/audio/speech");
    // gpt-4o-mini-transcribe supersedes whisper-1 -- OpenAI reports notably
    // better accuracy on accents and noisy/short clips, at a comparable price.
    private static final String TRANSCRIBE_MODEL = "gpt-4o-mini-transcribe";
    // Biases the transcription toward this app's actual vocabulary (short
    // customer-support utterances about orders, not generic dictation) --
    // cuts down on the model latching onto an unrelated, more "common" phrase
    // for a short or accented clip it isn't fully sure about.
    private static final String TRANSCRIBE_PROMPT =
            "Customer support voice message for CityDrop, a package delivery app. "
                    + "Topics: order status, cancel an order, package weight in pounds, "
                    + "delivery addresses in San Francisco, robot or drone delivery.";
    private static final String TTS_MODEL = "tts-1";
    private static final String TTS_VOICE = "alloy";

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final ObjectMapper objectMapper;
    private final String apiKey;
    private final String model;

    OpenAiClient(
            ObjectMapper objectMapper,
            @Value("${openai.api-key:}") String apiKey,
            @Value("${openai.model:gpt-4o-mini}") String model
    ) {
        this.objectMapper = objectMapper;
        this.apiKey = apiKey;
        this.model = model;
    }

    JsonNode createCompletion(List<Map<String, Object>> messages, List<Map<String, Object>> tools) {
        if (apiKey.isBlank()) {
            throw new ChatUnavailableException("Chat isn't configured yet (missing OPENAI_API_KEY).");
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", messages);
        if (tools != null && !tools.isEmpty()) {
            body.put("tools", tools);
        }
        // Route requests sharing our static prefix (system prompt + tool
        // definitions) to the same prompt cache, so low traffic doesn't
        // scatter them across backends and lose the cache hit. Bump the
        // version suffix whenever SYSTEM_PROMPT or TOOLS changes so a new
        // prefix doesn't keep targeting the old cache.
        body.put("prompt_cache_key", "citydrop-chat-v1");

        try {
            String requestJson = objectMapper.writeValueAsString(body);
            HttpRequest request = HttpRequest.newBuilder(ENDPOINT)
                    .timeout(Duration.ofSeconds(30))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestJson))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 300) {
                log.warn("OpenAI request failed with status {}: {}", response.statusCode(), response.body());
                throw new ChatUnavailableException("Chat service is temporarily unavailable.");
            }
            JsonNode json = objectMapper.readTree(response.body());
            // OpenAI caches an identical prompt prefix (our static system
            // prompt + tool definitions) automatically once it's >= 1024
            // tokens, billing the hit at a discount. `cached_tokens` lets us
            // confirm it's actually landing rather than assume it.
            JsonNode usage = json.path("usage");
            log.info("OpenAI completion: prompt_tokens={}, cached_tokens={}, completion_tokens={}",
                    usage.path("prompt_tokens").asInt(0),
                    usage.path("prompt_tokens_details").path("cached_tokens").asInt(0),
                    usage.path("completion_tokens").asInt(0));
            return json;
        } catch (IOException e) {
            log.warn("OpenAI request failed", e);
            throw new ChatUnavailableException("Chat service is temporarily unavailable.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ChatUnavailableException("Chat service is temporarily unavailable.");
        }
    }

    // Feature 4, voice (ASR): OpenAI's transcription endpoint takes
    // multipart/form-data, not JSON -- HttpClient has no built-in multipart
    // support, so the body is hand-assembled below.
    String transcribe(byte[] audioBytes, String filename, String contentType) {
        if (apiKey.isBlank()) {
            throw new ChatUnavailableException("Chat isn't configured yet (missing OPENAI_API_KEY).");
        }

        String boundary = "CityDropBoundary" + System.nanoTime();
        byte[] body = multipartBody(boundary, audioBytes, filename, contentType);

        try {
            HttpRequest request = HttpRequest.newBuilder(TRANSCRIPTION_ENDPOINT)
                    .timeout(Duration.ofSeconds(30))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 300) {
                log.warn("OpenAI transcription request failed with status {}: {}",
                        response.statusCode(), response.body());
                throw new ChatUnavailableException("Voice transcription is temporarily unavailable.");
            }
            return objectMapper.readTree(response.body()).get("text").asString();
        } catch (IOException e) {
            log.warn("OpenAI transcription request failed", e);
            throw new ChatUnavailableException("Voice transcription is temporarily unavailable.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ChatUnavailableException("Voice transcription is temporarily unavailable.");
        }
    }

    // Feature 4, voice (TTS): unlike every other call here, the response
    // body is raw audio bytes, not JSON -- read as ofByteArray, not parsed.
    byte[] synthesizeSpeech(String text) {
        if (apiKey.isBlank()) {
            throw new ChatUnavailableException("Chat isn't configured yet (missing OPENAI_API_KEY).");
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", TTS_MODEL);
        body.put("input", text);
        body.put("voice", TTS_VOICE);

        try {
            String requestJson = objectMapper.writeValueAsString(body);
            HttpRequest request = HttpRequest.newBuilder(SPEECH_ENDPOINT)
                    .timeout(Duration.ofSeconds(30))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestJson))
                    .build();

            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() >= 300) {
                log.warn("OpenAI speech request failed with status {}", response.statusCode());
                throw new ChatUnavailableException("Voice reply is temporarily unavailable.");
            }
            return response.body();
        } catch (IOException e) {
            log.warn("OpenAI speech request failed", e);
            throw new ChatUnavailableException("Voice reply is temporarily unavailable.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ChatUnavailableException("Voice reply is temporarily unavailable.");
        }
    }

    private static byte[] multipartBody(String boundary, byte[] fileBytes, String filename, String contentType) {
        String CRLF = "\r\n";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            out.write(("--" + boundary + CRLF).getBytes(StandardCharsets.UTF_8));
            out.write(("Content-Disposition: form-data; name=\"model\"" + CRLF + CRLF).getBytes(StandardCharsets.UTF_8));
            out.write((TRANSCRIBE_MODEL + CRLF).getBytes(StandardCharsets.UTF_8));

            out.write(("--" + boundary + CRLF).getBytes(StandardCharsets.UTF_8));
            out.write(("Content-Disposition: form-data; name=\"prompt\"" + CRLF + CRLF).getBytes(StandardCharsets.UTF_8));
            out.write((TRANSCRIBE_PROMPT + CRLF).getBytes(StandardCharsets.UTF_8));

            out.write(("--" + boundary + CRLF).getBytes(StandardCharsets.UTF_8));
            out.write(("Content-Disposition: form-data; name=\"file\"; filename=\"" + filename + "\"" + CRLF)
                    .getBytes(StandardCharsets.UTF_8));
            out.write(("Content-Type: " + contentType + CRLF + CRLF).getBytes(StandardCharsets.UTF_8));
            out.write(fileBytes);
            out.write(CRLF.getBytes(StandardCharsets.UTF_8));

            out.write(("--" + boundary + "--" + CRLF).getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to build multipart request body", e);
        }
        return out.toByteArray();
    }
}

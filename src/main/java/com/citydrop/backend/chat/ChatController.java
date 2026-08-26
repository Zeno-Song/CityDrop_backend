package com.citydrop.backend.chat;

import com.citydrop.backend.db.entities.UserEntity;
import com.citydrop.backend.models.requests.ChatRequest;
import com.citydrop.backend.models.requests.SpeakRequest;
import com.citydrop.backend.models.responses.ChatResponse;
import com.citydrop.backend.user.UserService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.security.Principal;
import java.util.Map;

@RestController
public class ChatController {

    private final ChatService chatService;
    private final UserService userService;

    public ChatController(ChatService chatService, UserService userService) {
        this.chatService = chatService;
        this.userService = userService;
    }

    @PostMapping("/chat")
    public ChatResponse chat(@RequestBody ChatRequest request, Principal principal) {
        UserEntity user = userService.findByUsername(principal.getName());
        ChatService.ChatOutcome outcome = chatService.reply(user.id(), request.message(), request.history());
        return new ChatResponse(
                outcome.text(), outcome.suggestCreateOrder(), outcome.offerHumanHelp(), outcome.suggestCancelOrderId());
    }

    // Feature 4, voice (ASR): a stateless proxy to OpenAI's transcription API
    // -- doesn't touch order/user data, so it doesn't need userId at all,
    // just the same session-authenticated boundary every other endpoint has
    // (see AppConfig's catch-all).
    @PostMapping(value = "/chat/transcribe", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, String> transcribe(@RequestParam("audio") MultipartFile audio) throws IOException {
        String filename = audio.getOriginalFilename() != null ? audio.getOriginalFilename() : "recording.webm";
        String contentType = audio.getContentType() != null ? audio.getContentType() : "audio/webm";
        String text = chatService.transcribe(audio.getBytes(), filename, contentType);
        return Map.of("text", text);
    }

    // Feature 4, voice (TTS): same stateless-proxy shape as /chat/transcribe,
    // just JSON in instead of multipart, and raw audio bytes out instead of
    // JSON.
    @PostMapping("/chat/speak")
    public ResponseEntity<byte[]> speak(@RequestBody SpeakRequest request) {
        byte[] audio = chatService.synthesizeSpeech(request.text());
        return ResponseEntity.ok().contentType(MediaType.valueOf("audio/mpeg")).body(audio);
    }
}

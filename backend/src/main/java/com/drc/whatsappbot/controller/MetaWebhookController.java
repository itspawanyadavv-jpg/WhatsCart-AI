package com.drc.whatsappbot.controller;

import com.drc.whatsappbot.config.BotProperties;
import com.drc.whatsappbot.enums.MessageChannel;
import com.drc.whatsappbot.service.ConversationEngineService;
import com.drc.whatsappbot.service.LlmHinglishParserService;
import com.drc.whatsappbot.service.WhatsAppGatewayService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/webhooks/whatsapp")
public class MetaWebhookController {

    private static final Logger log = LoggerFactory.getLogger(MetaWebhookController.class);

    private final BotProperties props;
    private final WhatsAppGatewayService gatewayService;
    private final ConversationEngineService engine;
    private final LlmHinglishParserService parserService;
    private final ObjectMapper mapper;

    public MetaWebhookController(BotProperties props,
                                 WhatsAppGatewayService gatewayService,
                                 ConversationEngineService engine,
                                 LlmHinglishParserService parserService,
                                 ObjectMapper mapper) {
        this.props = props;
        this.gatewayService = gatewayService;
        this.engine = engine;
        this.parserService = parserService;
        this.mapper = mapper;
    }

    @GetMapping
    public ResponseEntity<String> verifyWebhook(
            @RequestParam(name = "hub.mode", required = false) String mode,
            @RequestParam(name = "hub.verify_token", required = false) String verifyToken,
            @RequestParam(name = "hub.challenge", required = false) String challenge) {
        if ("subscribe".equals(mode) && props.getWhatsapp().getVerifyToken().equals(verifyToken)) {
            return ResponseEntity.ok(challenge == null ? "" : challenge);
        }
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body("Invalid verify token");
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> receiveWebhook(
            @RequestBody String rawBody,
            @RequestHeader(value = "X-Hub-Signature-256", required = false) String signature) {
        if (!gatewayService.verifyMetaSignature(rawBody, signature)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Invalid signature"));
        }

        int processed = 0;
        try {
            JsonNode root = mapper.readTree(rawBody);
            for (JsonNode entry : root.path("entry")) {
                for (JsonNode change : entry.path("changes")) {
                    JsonNode value = change.path("value");
                    Map<String, String> contactNames = new HashMap<>();
                    for (JsonNode contact : value.path("contacts")) {
                        String waId = contact.path("wa_id").asText("");
                        String name = contact.path("profile").path("name").asText("");
                        if (!waId.isBlank() && !name.isBlank()) {
                            contactNames.put(waId, name);
                        }
                    }

                    for (JsonNode msg : value.path("messages")) {
                        String from = msg.path("from").asText("");
                        String name = contactNames.getOrDefault(from, "WhatsApp User");
                        String type = msg.path("type").asText("");
                        if ("image".equals(type) || "audio".equals(type) || "voice".equals(type) || "document".equals(type)) {
                            JsonNode mediaNode = msg.path(type);
                            String caption = mediaNode.path("caption").asText("");
                            String mimeType = mediaNode.path("mime_type").asText(
                                    ("audio".equals(type) || "voice".equals(type)) ? "audio/ogg" : "image/jpeg");
                            String mediaId = mediaNode.path("id").asText("");
                            byte[] mediaBytes = (!mediaId.isBlank() && parserService.isGeminiMultimodalReady())
                                    ? gatewayService.downloadMetaMedia(mediaId)
                                    : null;
                            if (!from.isBlank()) {
                                engine.handleInboundMedia(from, name, mediaBytes, mimeType, caption, MessageChannel.META_WHATSAPP);
                                processed++;
                            }
                            continue;
                        }
                        String text = extractMessageText(msg);
                        if (!from.isBlank() && !text.isBlank()) {
                            engine.handleInboundMessage(from, name, text, MessageChannel.META_WHATSAPP);
                            processed++;
                        }
                    }
                }
            }
        } catch (Exception ex) {
            log.warn("Failed to parse Meta webhook payload: {}", ex.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", "Malformed payload"));
        }

        return ResponseEntity.ok(Map.of("status", "ok", "processedMessages", processed));
    }

    private String extractMessageText(JsonNode msg) {
        String type = msg.path("type").asText("");
        if ("text".equals(type)) {
            return msg.path("text").path("body").asText("");
        }
        if ("interactive".equals(type)) {
            JsonNode interactive = msg.path("interactive");
            String buttonTitle = interactive.path("button_reply").path("title").asText("");
            if (!buttonTitle.isBlank()) return buttonTitle;
            return interactive.path("list_reply").path("title").asText("");
        }
        if ("button".equals(type)) {
            return msg.path("button").path("text").asText("");
        }
        if ("location".equals(type)) {
            JsonNode loc = msg.path("location");
            String name = loc.path("name").asText("");
            String address = loc.path("address").asText("");
            String lat = loc.path("latitude").asText("");
            String lon = loc.path("longitude").asText("");
            String combined = (name + " " + address).trim();
            if (!combined.isBlank()) {
                return "deliver at " + combined;
            }
            if (!lat.isBlank() && !lon.isBlank()) {
                return "deliver at Pin Location (" + lat + ", " + lon + ")";
            }
        }
        return "";
    }
}


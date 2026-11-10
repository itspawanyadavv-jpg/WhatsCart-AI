package com.drc.whatsappbot.service;

import com.drc.whatsappbot.config.BotProperties;
import com.drc.whatsappbot.enums.MessageChannel;
import com.drc.whatsappbot.enums.MessageDirection;
import com.drc.whatsappbot.model.ChatMessage;
import com.drc.whatsappbot.repository.ChatMessageRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.*;

@Service
public class WhatsAppGatewayService {

    private static final Logger log = LoggerFactory.getLogger(WhatsAppGatewayService.class);

    private final ChatMessageRepository messageRepo;
    private final SseEventBusService sseBus;
    private final BotProperties props;
    private final ObjectMapper mapper;
    private final RestClient restClient;

    @Autowired
    public WhatsAppGatewayService(ChatMessageRepository messageRepo,
                                  SseEventBusService sseBus,
                                  BotProperties props,
                                  ObjectMapper mapper) {
        this(messageRepo, sseBus, props, mapper, RestClient.builder().build());
    }

    public WhatsAppGatewayService(ChatMessageRepository messageRepo,
                                  SseEventBusService sseBus,
                                  BotProperties props,
                                  ObjectMapper mapper,
                                  RestClient restClient) {
        this.messageRepo = messageRepo;
        this.sseBus = sseBus;
        this.props = props;
        this.mapper = mapper;
        this.restClient = restClient;
    }

    public ChatMessage recordInbound(String phone, MessageChannel channel, String body, Map<String, Object> metadata) {
        ChatMessage msg = new ChatMessage();
        msg.setPhone(phone);
        msg.setDirection(MessageDirection.INBOUND);
        msg.setChannel(channel);
        msg.setBody(body);
        msg.setMetadataJson(toJson(metadata));
        msg.setCreatedAt(LocalDateTime.now());
        ChatMessage saved = messageRepo.save(msg);
        sseBus.publish("chat:" + phone, "message", saved);
        return saved;
    }

    public ChatMessage sendReply(String phone, MessageChannel channel, String body, Map<String, Object> metadata) {
        return sendReply(phone, channel, body, metadata, true);
    }

    public ChatMessage sendReply(String phone, MessageChannel channel, String body, Map<String, Object> metadata, boolean dispatchExternal) {
        ChatMessage msg = new ChatMessage();
        msg.setPhone(phone);
        msg.setDirection(MessageDirection.OUTBOUND);
        msg.setChannel(channel);
        msg.setBody(body);
        msg.setMetadataJson(toJson(metadata));
        msg.setCreatedAt(LocalDateTime.now());
        ChatMessage saved = messageRepo.save(msg);

        // Push live to Web Chat Simulator via SSE
        sseBus.publish("chat:" + phone, "message", saved);

        // Never leak SIMULATOR traffic to real Meta/Twilio numbers
        if (!dispatchExternal || channel == MessageChannel.SIMULATOR) {
            return saved;
        }

        if (channel == MessageChannel.META_WHATSAPP) {
            sendViaMetaCloudApi(phone, body, metadata);
        } else if (channel == MessageChannel.TWILIO_WHATSAPP) {
            sendViaTwilio(phone, body);
        }

        return saved;
    }

    public boolean isMetaConfigured() {
        return props.getWhatsapp().getAccessToken() != null && !props.getWhatsapp().getAccessToken().isBlank()
                && props.getWhatsapp().getPhoneNumberId() != null && !props.getWhatsapp().getPhoneNumberId().isBlank();
    }

    public boolean isTwilioConfigured() {
        return props.getWhatsapp().getTwilioAccountSid() != null && !props.getWhatsapp().getTwilioAccountSid().isBlank()
                && props.getWhatsapp().getTwilioAuthToken() != null && !props.getWhatsapp().getTwilioAuthToken().isBlank()
                && props.getWhatsapp().getTwilioFromNumber() != null && !props.getWhatsapp().getTwilioFromNumber().isBlank();
    }

    public boolean verifyMetaSignature(String rawBody, String signatureHeader) {
        String appSecret = props.getWhatsapp().getAppSecret();
        if (appSecret == null || appSecret.isBlank()) {
            return !props.getSecurity().isStrictWebhookVerification();
        }
        if (signatureHeader == null || !signatureHeader.startsWith("sha256=")) {
            return false;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(appSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String expected = "sha256=" + HexFormat.of().formatHex(mac.doFinal(rawBody.getBytes(StandardCharsets.UTF_8)));
            return MessageDigest.isEqual(
                    expected.getBytes(StandardCharsets.UTF_8),
                    signatureHeader.trim().getBytes(StandardCharsets.UTF_8)
            );
        } catch (Exception e) {
            return false;
        }
    }

    public boolean verifyTwilioSignature(String requestUrl, Map<String, String> params, String signatureHeader) {
        String authToken = props.getWhatsapp().getTwilioAuthToken();
        if (authToken == null || authToken.isBlank()) {
            return !props.getSecurity().isStrictWebhookVerification();
        }
        if (signatureHeader == null || signatureHeader.isBlank() || requestUrl == null) {
            return false;
        }
        try {
            StringBuilder data = new StringBuilder(requestUrl);
            if (params != null && !params.isEmpty()) {
                List<String> keys = new ArrayList<>(params.keySet());
                Collections.sort(keys);
                for (String key : keys) {
                    data.append(key).append(params.get(key) == null ? "" : params.get(key));
                }
            }
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(authToken.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
            String expected = Base64.getEncoder().encodeToString(mac.doFinal(data.toString().getBytes(StandardCharsets.UTF_8)));
            return MessageDigest.isEqual(
                    expected.getBytes(StandardCharsets.UTF_8),
                    signatureHeader.trim().getBytes(StandardCharsets.UTF_8)
            );
        } catch (Exception e) {
            return false;
        }
    }

    public byte[] downloadMetaMedia(String mediaId) {
        if (!isMetaConfigured() || mediaId == null || mediaId.isBlank()) {
            return null;
        }
        try {
            JsonNode mediaInfo = restClient.get()
                    .uri("https://graph.facebook.com/v21.0/" + mediaId.trim())
                    .header("Authorization", "Bearer " + props.getWhatsapp().getAccessToken())
                    .retrieve()
                    .body(JsonNode.class);
            String downloadUrl = mediaInfo == null ? "" : mediaInfo.path("url").asText("");
            if (downloadUrl.isBlank()) {
                return null;
            }
            return restClient.get()
                    .uri(downloadUrl)
                    .header("Authorization", "Bearer " + props.getWhatsapp().getAccessToken())
                    .retrieve()
                    .body(byte[].class);
        } catch (Exception ex) {
            log.warn("Failed to download Meta WhatsApp media {}: {}", mediaId, ex.getMessage());
            return null;
        }
    }

    private void sendViaMetaCloudApi(String toPhone, String text, Map<String, Object> metadata) {
        if (!isMetaConfigured()) {
            return;
        }
        try {
            String cleanPhone = toPhone.replaceAll("[^0-9]", "");
            Map<String, Object> payload = buildMetaPayload(cleanPhone, text, metadata);

            restClient.post()
                    .uri("https://graph.facebook.com/v21.0/" + props.getWhatsapp().getPhoneNumberId() + "/messages")
                    .header("Authorization", "Bearer " + props.getWhatsapp().getAccessToken())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception ex) {
            log.warn("Meta Cloud API outbound dispatch failed for {}: {}", toPhone, ex.getMessage());
        }
    }

    public Map<String, Object> buildMetaPayload(String cleanPhone, String text, Map<String, Object> metadata) {
        if (metadata != null && metadata.get("quickReplies") instanceof List<?> rawReplies) {
            List<String> buttonTitles = rawReplies.stream()
                    .filter(String.class::isInstance)
                    .map(String.class::cast)
                    .map(String::trim)
                    .filter(s -> !s.isEmpty() && s.length() <= 20)
                    .limit(3)
                    .toList();

            if (!buttonTitles.isEmpty() && text.length() <= 1024) {
                List<Map<String, Object>> buttons = new ArrayList<>();
                for (int i = 0; i < buttonTitles.size(); i++) {
                    String title = buttonTitles.get(i);
                    buttons.add(Map.of(
                            "type", "reply",
                            "reply", Map.of("id", "qr_" + (i + 1), "title", title)
                    ));
                }
                return Map.of(
                        "messaging_product", "whatsapp",
                        "to", cleanPhone,
                        "type", "interactive",
                        "interactive", Map.of(
                                "type", "button",
                                "body", Map.of("text", text),
                                "action", Map.of("buttons", buttons)
                        )
                );
            }
        }

        return Map.of(
                "messaging_product", "whatsapp",
                "to", cleanPhone,
                "type", "text",
                "text", Map.of("preview_url", true, "body", text)
        );
    }

    private void sendViaTwilio(String toPhone, String text) {
        if (!isTwilioConfigured()) {
            return;
        }
        try {
            String formattedTo = toPhone.startsWith("whatsapp:") ? toPhone : "whatsapp:" + toPhone;
            String formattedFrom = props.getWhatsapp().getTwilioFromNumber().startsWith("whatsapp:")
                    ? props.getWhatsapp().getTwilioFromNumber()
                    : "whatsapp:" + props.getWhatsapp().getTwilioFromNumber();

            MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.put("To", List.of(formattedTo));
            form.put("From", List.of(formattedFrom));
            form.put("Body", List.of(text));

            restClient.post()
                    .uri("https://api.twilio.com/2010-04-01/Accounts/" + props.getWhatsapp().getTwilioAccountSid() + "/Messages.json")
                    .headers(h -> h.setBasicAuth(
                            props.getWhatsapp().getTwilioAccountSid(),
                            props.getWhatsapp().getTwilioAuthToken()))
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception ex) {
            log.warn("Twilio WhatsApp outbound dispatch failed for {}: {}", toPhone, ex.getMessage());
        }
    }

    private String toJson(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return null;
        }
        try {
            return mapper.writeValueAsString(metadata);
        } catch (Exception e) {
            return null;
        }
    }
}


package com.drc.whatsappbot.controller;

import com.drc.whatsappbot.config.BotProperties;
import com.drc.whatsappbot.dto.ChatTurnResponse;
import com.drc.whatsappbot.enums.MessageChannel;
import com.drc.whatsappbot.model.ChatMessage;
import com.drc.whatsappbot.service.ConversationEngineService;
import com.drc.whatsappbot.service.LlmHinglishParserService;
import com.drc.whatsappbot.service.WhatsAppGatewayService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClient;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/webhooks/twilio")
public class TwilioWebhookController {

    private static final Logger log = LoggerFactory.getLogger(TwilioWebhookController.class);

    private final ConversationEngineService engine;
    private final WhatsAppGatewayService gatewayService;
    private final LlmHinglishParserService parserService;
    private final BotProperties props;

    public TwilioWebhookController(ConversationEngineService engine,
                                 WhatsAppGatewayService gatewayService,
                                 LlmHinglishParserService parserService,
                                 BotProperties props) {
        this.engine = engine;
        this.gatewayService = gatewayService;
        this.parserService = parserService;
        this.props = props;
    }

    @PostMapping(consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE, produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> receiveTwilioWebhook(
            @RequestParam Map<String, String> allParams,
            @RequestHeader(value = "X-Twilio-Signature", required = false) String signature,
            HttpServletRequest request) {

        Map<String, String> paramCopy = new LinkedHashMap<>(allParams);
        String querySuffix = (request.getQueryString() != null && !request.getQueryString().isBlank())
                ? "?" + request.getQueryString()
                : "";
        String fullUrl = request.getRequestURL().toString() + querySuffix;
        String publicBase = props.getRazorpay().getCallbackBaseUrl();
        String publicUrl = (publicBase != null && !publicBase.isBlank())
                ? publicBase.replaceAll("/+$", "") + request.getRequestURI() + querySuffix
                : null;

        String hostHeader = request.getHeader("Host");
        String httpsHostUrl = (hostHeader != null && !hostHeader.isBlank()
                && !hostHeader.startsWith("localhost") && !hostHeader.startsWith("127.0.0.1")
                && fullUrl.startsWith("http://"))
                ? "https://" + hostHeader.trim() + request.getRequestURI() + querySuffix
                : null;

        boolean sigValid = gatewayService.verifyTwilioSignature(fullUrl, paramCopy, signature)
                || (publicUrl != null && gatewayService.verifyTwilioSignature(publicUrl, paramCopy, signature))
                || (httpsHostUrl != null && gatewayService.verifyTwilioSignature(httpsHostUrl, paramCopy, signature));
        if (!sigValid) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body("<?xml version=\"1.0\" encoding=\"UTF-8\"?><Response></Response>");
        }

        String from = allParams.getOrDefault("From", "");
        String body = allParams.getOrDefault("Body", "");
        String profileName = allParams.get("ProfileName");
        int numMedia = 0;
        try {
            numMedia = Integer.parseInt(allParams.getOrDefault("NumMedia", "0"));
        } catch (NumberFormatException ignored) {
            numMedia = 0;
        }

        ChatTurnResponse turn;
        if (numMedia > 0) {
            String mediaUrl = allParams.get("MediaUrl0");
            String mediaType = allParams.get("MediaContentType0");
            byte[] mediaBytes = downloadTwilioMediaIfMultimodalReady(mediaUrl);
            turn = engine.handleInboundMedia(from, profileName, mediaBytes, mediaType, body, MessageChannel.TWILIO_WHATSAPP);
        } else {
            turn = engine.handleInboundMessage(from, profileName, body, MessageChannel.TWILIO_WHATSAPP);
        }
        List<ChatMessage> msgs = turn.messages();
        String reply = msgs.isEmpty() ? "Thank you for contacting DRC Krishna Gen Store."
                : msgs.get(msgs.size() - 1).getBody();

        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Response><Message>"
                + escapeXml(reply)
                + "</Message></Response>";

        return ResponseEntity.ok(xml);
    }

    private byte[] downloadTwilioMediaIfMultimodalReady(String mediaUrl) {
        if (mediaUrl == null || mediaUrl.isBlank() || !parserService.isGeminiMultimodalReady()) {
            return null;
        }
        try {
            RestClient.Builder builder = RestClient.builder();
            if (gatewayService.isTwilioConfigured()) {
                builder.defaultHeaders(h -> h.setBasicAuth(
                        props.getWhatsapp().getTwilioAccountSid(),
                        props.getWhatsapp().getTwilioAuthToken()));
            }
            return builder.build().get().uri(mediaUrl).retrieve().body(byte[].class);
        } catch (Exception ex) {
            log.warn("Failed to download Twilio media {}: {}", mediaUrl, ex.getMessage());
            return null;
        }
    }

    private String escapeXml(String input) {
        return input
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }
}


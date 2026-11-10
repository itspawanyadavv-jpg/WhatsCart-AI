package com.drc.whatsappbot;

import com.drc.whatsappbot.config.BotProperties;
import com.drc.whatsappbot.enums.MessageChannel;
import com.drc.whatsappbot.repository.ChatMessageRepository;
import com.drc.whatsappbot.service.DrcCatalogSyncService;
import com.drc.whatsappbot.service.SseEventBusService;
import com.drc.whatsappbot.service.WhatsAppGatewayService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class WebhookControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private DrcCatalogSyncService catalogService;

    @Autowired
    private ChatMessageRepository messageRepo;

    @Autowired
    private SseEventBusService sseBus;

    @Autowired
    private ObjectMapper mapper;

    @BeforeEach
    void init() {
        catalogService.seedFallbackCatalog(true);
    }

    @Test
    void verifiesMetaWebhookChallenge() throws Exception {
        mockMvc.perform(get("/api/webhooks/whatsapp")
                        .param("hub.mode", "subscribe")
                        .param("hub.verify_token", "test-verify-token")
                        .param("hub.challenge", "123456789"))
                .andExpect(status().isOk())
                .andExpect(content().string("123456789"));

        mockMvc.perform(get("/api/webhooks/whatsapp")
                        .param("hub.mode", "subscribe")
                        .param("hub.verify_token", "wrong-token")
                        .param("hub.challenge", "123456789"))
                .andExpect(status().isForbidden());
    }

    @Test
    void handlesMetaInboundWebhookMessage() throws Exception {
        String payload = """
                {
                  "object": "whatsapp_business_account",
                  "entry": [{
                    "changes": [{
                      "value": {
                        "contacts": [{"wa_id": "919811112222", "profile": {"name": "Priya"}}],
                        "messages": [{
                          "from": "919811112222",
                          "type": "text",
                          "text": {"body": "2 Tata salt aur 1 Parle-G bhejo"}
                        }]
                      }
                    }]
                  }]
                }
                """;

        mockMvc.perform(post("/api/webhooks/whatsapp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("\"processedMessages\":1")));
    }

    @Test
    void handlesTwilioWebhookAndReturnsTwiml() throws Exception {
        mockMvc.perform(post("/api/webhooks/twilio")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("From", "whatsapp:+919811113333")
                        .param("ProfileName", "Rajesh")
                        .param("Body", "1 Aashirvaad atta 5kg"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("<Response><Message>")))
                .andExpect(content().string(containsString("Aashirvaad Atta 5 kg")));
    }

    @Test
    void handlesMetaLocationMessagePayload() throws Exception {
        String payload = """
                {
                  "object": "whatsapp_business_account",
                  "entry": [{
                    "changes": [{
                      "value": {
                        "contacts": [{"wa_id": "919811114444", "profile": {"name": "Neha"}}],
                        "messages": [{
                          "from": "919811114444",
                          "type": "location",
                          "location": {
                            "latitude": 28.6139,
                            "longitude": 77.2090,
                            "name": "Krishna Heights",
                            "address": "Sector 12, Noida"
                          }
                        }]
                      }
                    }]
                  }]
                }
                """;

        mockMvc.perform(post("/api/webhooks/whatsapp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("\"processedMessages\":1")));

        var msgs = messageRepo.findByPhoneOrderByCreatedAtAscIdAsc("+919811114444");
        assertThat(msgs).isNotEmpty();
        assertThat(msgs.get(msgs.size() - 1).getBody()).contains("Krishna Heights Sector 12, Noida");
    }

    @Test
    void enforcesMetaAndTwilioSignaturesAndNeverLeaksSimulatorMessages() throws Exception {
        BotProperties props = new BotProperties();
        props.getWhatsapp().setAppSecret("meta_secret");
        props.getWhatsapp().setTwilioAuthToken("twilio_secret");
        props.getSecurity().setStrictWebhookVerification(true);

        WhatsAppGatewayService gw = new WhatsAppGatewayService(messageRepo, sseBus, props, mapper);

        assertThat(gw.verifyMetaSignature("{}", "sha256=invalid")).isFalse();
        assertThat(gw.verifyTwilioSignature("https://example.com/api/webhooks/twilio", Map.of("Body", "hi"), "bad"))
                .isFalse();

        // Compute valid Twilio HMAC-SHA1 signature for https URL + sorted params
        String url = "https://example.com/api/webhooks/twilio";
        Mac mac = Mac.getInstance("HmacSHA1");
        mac.init(new SecretKeySpec("twilio_secret".getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
        String validTwilioSig = Base64.getEncoder().encodeToString(
                mac.doFinal((url + "BodyhiFromwhatsapp:+919811115555").getBytes(StandardCharsets.UTF_8))
        );
        assertThat(gw.verifyTwilioSignature(url, Map.of("From", "whatsapp:+919811115555", "Body", "hi"), validTwilioSig))
                .isTrue();

        // Even if Meta/Twilio creds look configured, SIMULATOR replies only record locally
        props.getWhatsapp().setAccessToken("invalid-token-should-not-call");
        props.getWhatsapp().setPhoneNumberId("123456");
        var saved = gw.sendReply("+919811115555", MessageChannel.SIMULATOR, "Hello Simulator", Map.of());
        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getChannel()).isEqualTo(MessageChannel.SIMULATOR);
    }

    @Test
    void handlesTwilioMediaWithoutTextGracefully() throws Exception {
        mockMvc.perform(post("/api/webhooks/twilio")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("From", "whatsapp:+919811116666")
                        .param("ProfileName", "Kavita")
                        .param("NumMedia", "1")
                        .param("MediaContentType0", "audio/ogg")
                        .param("MediaUrl0", "https://api.twilio.com/media/123")
                        .param("Body", ""))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Voice note received")));
    }
}


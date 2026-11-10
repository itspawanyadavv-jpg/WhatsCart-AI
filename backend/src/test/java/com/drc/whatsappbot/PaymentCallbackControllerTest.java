package com.drc.whatsappbot;

import com.drc.whatsappbot.config.BotProperties;
import com.drc.whatsappbot.controller.HealthController;
import com.drc.whatsappbot.dto.ChatTurnResponse;
import com.drc.whatsappbot.enums.BotOrderStatus;
import com.drc.whatsappbot.enums.BotPaymentStatus;
import com.drc.whatsappbot.enums.MessageChannel;
import com.drc.whatsappbot.repository.BotOrderRepository;
import com.drc.whatsappbot.service.ConversationEngineService;
import com.drc.whatsappbot.service.DrcCatalogSyncService;
import com.drc.whatsappbot.service.RazorpayPaymentLinkService;
import com.drc.whatsappbot.service.SseEventBusService;
import com.drc.whatsappbot.service.WhatsAppGatewayService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class PaymentCallbackControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ConversationEngineService engine;

    @Autowired
    private DrcCatalogSyncService catalogService;

    @Autowired
    private BotOrderRepository orderRepo;

    @Autowired
    private RazorpayPaymentLinkService paymentLinkService;

    @Autowired
    private WhatsAppGatewayService gatewayService;

    @BeforeEach
    void init() {
        catalogService.seedFallbackCatalog(true);
    }

    @Test
    void rendersCheckoutPageAndCompletesDemoPayment() throws Exception {
        String phone = "+919844445555";
        engine.resetSession(phone, "Ananya");
        engine.handleInboundMessage(phone, "Ananya", "5kg Aashirvaad atta deliver at Sector 18", MessageChannel.SIMULATOR);
        ChatTurnResponse upiTurn = engine.handleInboundMessage(phone, "Ananya", "UPI", MessageChannel.SIMULATOR);
        String orderCode = upiTurn.activeOrder().getOrderCode();

        mockMvc.perform(get("/api/payments/checkout").param("orderCode", orderCode))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("UPI Checkout")))
                .andExpect(content().string(containsString("upi://pay")))
                .andExpect(content().string(containsString("Simulate UPI Paid (Demo Mode)")));

        mockMvc.perform(post("/api/payments/checkout/confirm").param("orderCode", orderCode))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Payment Confirmed")))
                .andExpect(content().string(containsString(upiTurn.activeOrder().getDeliveryOtp())));

        var updated = orderRepo.findByOrderCode(orderCode).orElseThrow();
        assertThat(updated.getPaymentStatus()).isEqualTo(BotPaymentStatus.PAID);
        assertThat(updated.getStatus()).isEqualTo(BotOrderStatus.CONFIRMED);
    }

    @Test
    void healthControllerRespectsConfiguredLlmProvider() {
        BotProperties props = new BotProperties();
        props.getLlm().setGeminiApiKey("gem-key");
        props.getLlm().setOpenaiApiKey("oai-key");
        props.getLlm().setProvider("FALLBACK");

        HealthController controller = new HealthController(props, catalogService, paymentLinkService, gatewayService);
        assertThat(controller.health().get("llmMode")).isEqualTo("HINGLISH_NLP_FALLBACK");

        props.getLlm().setProvider("OPENAI");
        assertThat(controller.health().get("llmMode")).isEqualTo("OPENAI (gpt-4o-mini)");
    }

    @Test
    void rootLandingPageAndSseDeadEmitterCleanupWorkCleanly() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("WhatsApp AI Bot API")))
                .andExpect(content().string(containsString("/api/health")));

        SseEventBusService bus = new SseEventBusService();
        SseEmitter failingEmitter = new SseEmitter(0L) {
            @Override
            public void send(SseEventBuilder builder) throws IOException {
                throw new IOException("Closed socket");
            }
        };
        bus.dispatchEvent("chat:+919999999999", List.of(failingEmitter), "message", Map.of("ok", true));
        SseEmitter subscribed = bus.subscribe("chat:+919999999999");
        assertThat(subscribed).isNotNull();
        bus.publish("chat:+919999999999", "message", Map.of("ok", true));
        bus.sendHeartbeats();
        bus.destroy();
    }
}


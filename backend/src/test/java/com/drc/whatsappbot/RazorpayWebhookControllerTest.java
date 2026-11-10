package com.drc.whatsappbot;

import com.drc.whatsappbot.dto.ChatTurnResponse;
import com.drc.whatsappbot.enums.BotOrderStatus;
import com.drc.whatsappbot.enums.BotPaymentStatus;
import com.drc.whatsappbot.enums.MessageChannel;
import com.drc.whatsappbot.repository.BotOrderRepository;
import com.drc.whatsappbot.service.ConversationEngineService;
import com.drc.whatsappbot.service.DrcCatalogSyncService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "bot.razorpay.webhook-secret=test_rzp_secret"
})
class RazorpayWebhookControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ConversationEngineService engine;

    @Autowired
    private DrcCatalogSyncService catalogService;

    @Autowired
    private BotOrderRepository orderRepo;

    @BeforeEach
    void setup() {
        catalogService.seedFallbackCatalog(true);
    }

    @Test
    void verifiesSignatureAndConfirmsOrderOnPaymentLinkPaid() throws Exception {
        String orderCode = createPendingUpiOrder("+919822223333");
        String payload = """
                {
                  "event": "payment_link.paid",
                  "payload": {
                    "payment_link": {
                      "entity": {
                        "id": "plink_demo_%s",
                        "reference_id": "%s"
                      }
                    },
                    "payment": {
                      "entity": {
                        "id": "pay_rzp_777"
                      }
                    }
                  }
                }
                """.formatted(orderCode.replaceAll("[^A-Za-z0-9]", ""), orderCode);

        // Missing/wrong signature -> 401
        mockMvc.perform(post("/api/webhooks/razorpay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Razorpay-Signature", "invalid")
                        .content(payload))
                .andExpect(status().isUnauthorized());

        // Valid signature -> confirms order
        String sig = hmac("test_rzp_secret", payload);
        mockMvc.perform(post("/api/webhooks/razorpay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Razorpay-Signature", sig)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("confirmed")));

        var updated = orderRepo.findByOrderCode(orderCode).orElseThrow();
        assertThat(updated.getPaymentStatus()).isEqualTo(BotPaymentStatus.PAID);
        assertThat(updated.getStatus()).isEqualTo(BotOrderStatus.CONFIRMED);
        assertThat(updated.getRazorpayPaymentId()).isEqualTo("pay_rzp_777");

        // Callback HTML landing page renders order confirmation & OTP
        mockMvc.perform(get("/api/payments/callback").param("orderCode", orderCode))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(content().string(containsString(orderCode)))
                .andExpect(content().string(containsString(updated.getDeliveryOtp())));
    }

    @Test
    void notifiesCustomerOnPaymentLinkExpired() throws Exception {
        String phone = "+919822224444";
        String orderCode = createPendingUpiOrder(phone);
        String payload = """
                {
                  "event": "payment_link.expired",
                  "payload": {
                    "payment_link": {
                      "entity": {
                        "id": "plink_exp_1",
                        "reference_id": "%s"
                      }
                    }
                  }
                }
                """.formatted(orderCode);

        String sig = hmac("test_rzp_secret", payload);
        mockMvc.perform(post("/api/webhooks/razorpay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Razorpay-Signature", sig)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("notified")));

        var order = orderRepo.findByOrderCode(orderCode).orElseThrow();
        assertThat(order.getPaymentStatus()).isEqualTo(BotPaymentStatus.PENDING);

        ChatTurnResponse turn = engine.getSessionState(phone);
        assertThat(turn.messages().get(turn.messages().size() - 1).getBody())
                .contains("expired");
    }

    private String createPendingUpiOrder(String phone) {
        engine.resetSession(phone, "Priya");
        engine.handleInboundMessage(phone, "Priya", "5kg Aashirvaad atta deliver at Flat 10, MG Road", MessageChannel.SIMULATOR);
        ChatTurnResponse upi = engine.handleInboundMessage(phone, "Priya", "UPI", MessageChannel.SIMULATOR);
        return upi.activeOrder().getOrderCode();
    }

    private String hmac(String secret, String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    }
}

package com.drc.whatsappbot.controller;

import com.drc.whatsappbot.repository.BotOrderRepository;
import com.drc.whatsappbot.service.ConversationEngineService;
import com.drc.whatsappbot.service.RazorpayPaymentLinkService;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/webhooks/razorpay")
public class RazorpayWebhookController {

    private static final Logger log = LoggerFactory.getLogger(RazorpayWebhookController.class);

    private final RazorpayPaymentLinkService paymentLinkService;
    private final BotOrderRepository orderRepo;
    private final ConversationEngineService engine;

    public RazorpayWebhookController(RazorpayPaymentLinkService paymentLinkService,
                                     BotOrderRepository orderRepo,
                                     ConversationEngineService engine) {
        this.paymentLinkService = paymentLinkService;
        this.orderRepo = orderRepo;
        this.engine = engine;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> handleWebhook(
            @RequestBody String rawBody,
            @RequestHeader(value = "X-Razorpay-Signature", required = false) String signature) {
        if (!paymentLinkService.verifyWebhookSignature(rawBody, signature)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Invalid Razorpay signature"));
        }

        try {
            JsonNode root = paymentLinkService.parseWebhookPayload(rawBody);
            String event = root.path("event").asText("");
            JsonNode plink = root.path("payload").path("payment_link").path("entity");
            String plinkId = plink.path("id").asText("");
            String referenceId = plink.path("reference_id").asText("");

            if ("payment_link.paid".equals(event)) {
                String paymentId = root.path("payload").path("payment").path("entity").path("id").asText("rzp_paid");
                String orderCode = resolveOrderCode(referenceId, plinkId);
                if (orderCode != null) {
                    engine.markPaymentSucceeded(orderCode, paymentId);
                    return ResponseEntity.ok(Map.of("status", "confirmed", "orderCode", orderCode));
                }
            } else if ("payment_link.expired".equals(event)
                    || "payment_link.cancelled".equals(event)
                    || "payment.failed".equals(event)) {
                if (referenceId.isBlank()) {
                    referenceId = root.path("payload").path("payment").path("entity").path("notes").path("order_code").asText("");
                }
                String orderCode = resolveOrderCode(referenceId, plinkId);
                if (orderCode != null) {
                    engine.handlePaymentLinkFailureOrExpiry(orderCode, event);
                    return ResponseEntity.ok(Map.of("status", "notified", "event", event, "orderCode", orderCode));
                }
            }
        } catch (IllegalStateException ex) {
            log.warn("Invalid state for Razorpay webhook: {}", ex.getMessage());
            return ResponseEntity.ok(Map.of("status", "skipped", "reason", ex.getMessage()));
        } catch (Exception ex) {
            log.warn("Error handling Razorpay webhook: {}", ex.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", "Malformed webhook payload"));
        }

        return ResponseEntity.ok(Map.of("status", "ignored"));
    }

    private String resolveOrderCode(String referenceId, String plinkId) {
        if (referenceId != null && !referenceId.isBlank() && orderRepo.findByOrderCode(referenceId).isPresent()) {
            return referenceId;
        }
        if (plinkId != null && !plinkId.isBlank()) {
            return orderRepo.findByRazorpayPaymentLinkId(plinkId)
                    .map(o -> o.getOrderCode())
                    .orElse(null);
        }
        return null;
    }
}


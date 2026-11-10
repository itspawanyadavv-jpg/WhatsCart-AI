package com.drc.whatsappbot.controller;

import com.drc.whatsappbot.config.BotProperties;
import com.drc.whatsappbot.enums.BotPaymentStatus;
import com.drc.whatsappbot.model.BotOrder;
import com.drc.whatsappbot.repository.BotOrderRepository;
import com.drc.whatsappbot.service.ConversationEngineService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.HtmlUtils;

import java.math.RoundingMode;
import java.util.Optional;

@RestController
@RequestMapping("/api/payments")
public class PaymentCallbackController {

    private final BotOrderRepository orderRepo;
    private final ConversationEngineService engine;
    private final BotProperties props;

    public PaymentCallbackController(BotOrderRepository orderRepo,
                                     ConversationEngineService engine,
                                     BotProperties props) {
        this.orderRepo = orderRepo;
        this.engine = engine;
        this.props = props;
    }

    @GetMapping(value = "/callback", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> handleRazorpayCallback(
            @RequestParam(name = "orderCode", required = false) String orderCode,
            @RequestParam(name = "razorpay_payment_id", required = false) String paymentId,
            @RequestParam(name = "razorpay_payment_link_status", required = false) String linkStatus) {

        if (orderCode == null || orderCode.isBlank()) {
            return ResponseEntity.badRequest().body(renderPage("Invalid Payment Callback",
                    "Missing order reference. Please return to WhatsApp and check your order status.", false));
        }

        Optional<BotOrder> maybeOrder = orderRepo.findByOrderCode(orderCode);
        if (maybeOrder.isEmpty()) {
            return ResponseEntity.status(404).body(renderPage("Order Not Found",
                    "Could not find order " + HtmlUtils.htmlEscape(orderCode) + ".", false));
        }

        BotOrder order = maybeOrder.get();
        if ("paid".equalsIgnoreCase(linkStatus) && order.getPaymentStatus() == BotPaymentStatus.PENDING) {
            try {
                order = engine.markPaymentSucceeded(order.getOrderCode(), paymentId);
            } catch (IllegalStateException ex) {
                return ResponseEntity.ok(renderPage("Order Cannot Be Paid",
                        HtmlUtils.htmlEscape(ex.getMessage()), false));
            }
        }

        boolean paid = order.getPaymentStatus() == BotPaymentStatus.PAID;
        String title = paid ? "Payment Confirmed" : "Payment Pending";
        String amount = order.getTotal().setScale(2, RoundingMode.HALF_UP).toPlainString();
        String details = """
                <p><strong>Store:</strong> %s</p>
                <p><strong>Order ID:</strong> <code>%s</code></p>
                <p><strong>Amount:</strong> ₹%s</p>
                <p><strong>Status:</strong> %s (%s)</p>
                <p><strong>Delivery Address:</strong> %s</p>
                %s
                <p style="margin-top:16px;color:#475569;font-size:14px;">
                  You can now return to WhatsApp for live packing and delivery updates.
                </p>
                """.formatted(
                HtmlUtils.htmlEscape(props.getStoreName()),
                HtmlUtils.htmlEscape(order.getOrderCode()),
                HtmlUtils.htmlEscape(amount),
                HtmlUtils.htmlEscape(order.getStatus().name()),
                HtmlUtils.htmlEscape(order.getPaymentStatus().name()),
                HtmlUtils.htmlEscape(order.getDeliveryAddress()),
                paid && order.getDeliveryOtp() != null
                        ? "<p><strong>Delivery OTP:</strong> <code style=\"font-size:18px;\">" + HtmlUtils.htmlEscape(order.getDeliveryOtp()) + "</code></p>"
                        : ""
        );

        return ResponseEntity.ok(renderPage(title, details, paid));
    }

    private String renderPage(String title, String bodyHtml, boolean success) {
        String accent = success ? "#059669" : "#d97706";
        return """
                <!doctype html>
                <html lang="en">
                <head>
                  <meta charset="utf-8" />
                  <meta name="viewport" content="width=device-width,initial-scale=1" />
                  <title>%s - %s</title>
                  <style>
                    body { font-family: system-ui, -apple-system, sans-serif; background:#0b141a; color:#e2e8f0; display:grid; place-items:center; min-height:100vh; margin:0; padding:20px; }
                    .card { background:#111b21; border:1px solid rgba(255,255,255,.12); border-radius:16px; padding:24px; max-width:460px; width:100%%; box-shadow:0 16px 40px rgba(0,0,0,.45); }
                    h1 { margin:0 0 14px; font-size:22px; color:%s; }
                    p { margin:8px 0; line-height:1.5; }
                    code { background:#1f2c33; padding:2px 6px; border-radius:6px; color:#5eead4; }
                  </style>
                </head>
                <body>
                  <div class="card">
                    <h1>%s</h1>
                    %s
                  </div>
                </body>
                </html>
                """.formatted(
                HtmlUtils.htmlEscape(title),
                HtmlUtils.htmlEscape(props.getStoreName()),
                accent,
                HtmlUtils.htmlEscape(title),
                bodyHtml
        );
    }
}


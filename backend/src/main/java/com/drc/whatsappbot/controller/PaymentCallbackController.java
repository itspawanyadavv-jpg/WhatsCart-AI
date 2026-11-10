package com.drc.whatsappbot.controller;

import com.drc.whatsappbot.config.AdminAuthGuard;
import com.drc.whatsappbot.config.BotProperties;
import com.drc.whatsappbot.enums.BotOrderStatus;
import com.drc.whatsappbot.enums.BotPaymentStatus;
import com.drc.whatsappbot.model.BotOrder;
import com.drc.whatsappbot.model.BotOrderItem;
import com.drc.whatsappbot.repository.BotOrderRepository;
import com.drc.whatsappbot.service.ConversationEngineService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
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
    private final AdminAuthGuard adminAuthGuard;

    public PaymentCallbackController(BotOrderRepository orderRepo,
                                     ConversationEngineService engine,
                                     BotProperties props,
                                     AdminAuthGuard adminAuthGuard) {
        this.orderRepo = orderRepo;
        this.engine = engine;
        this.props = props;
        this.adminAuthGuard = adminAuthGuard;
    }

    @GetMapping(value = "/checkout", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> renderCheckoutPage(@RequestParam(name = "orderCode", required = false) String orderCode) {
        if (orderCode == null || orderCode.isBlank()) {
            return ResponseEntity.badRequest().body(renderPage("Invalid Checkout Link",
                    "Missing order reference. Please return to WhatsApp and check your order status.", false));
        }
        Optional<BotOrder> maybeOrder = orderRepo.findByOrderCode(orderCode);
        if (maybeOrder.isEmpty()) {
            return ResponseEntity.status(404).body(renderPage("Order Not Found",
                    "Could not find order " + HtmlUtils.htmlEscape(orderCode) + ".", false));
        }

        BotOrder order = maybeOrder.get();
        if (order.getStatus() == BotOrderStatus.CANCELLED) {
            return ResponseEntity.ok(renderPage("Order Cancelled",
                    "Order <code>" + HtmlUtils.htmlEscape(order.getOrderCode()) + "</code> was cancelled.", false));
        }
        if (order.getPaymentStatus() == BotPaymentStatus.PAID) {
            return handleRazorpayCallback(order.getOrderCode(), order.getRazorpayPaymentId(), "paid");
        }

        String amount = order.getTotal().setScale(2, RoundingMode.HALF_UP).toPlainString();
        StringBuilder itemsHtml = new StringBuilder("<ul style=\"padding-left:18px;margin:8px 0;color:#cbd5e1;font-size:14px;\">");
        for (BotOrderItem item : order.getItems()) {
            itemsHtml.append("<li>")
                    .append(item.getQuantity()).append("x ")
                    .append(HtmlUtils.htmlEscape(item.getProductName()))
                    .append(" — ₹").append(HtmlUtils.htmlEscape(item.getLineTotal().setScale(2, RoundingMode.HALF_UP).toPlainString()))
                    .append("</li>");
        }
        itemsHtml.append("</ul>");

        boolean showDemoButton = props.getSecurity().isAllowSimulatePay()
                && (props.getSecurity().getAdminApiKey() == null || props.getSecurity().getAdminApiKey().isBlank());
        String upiLink = order.getUpiDeepLink() != null ? order.getUpiDeepLink() : "";

        String body = """
                <p><strong>Store:</strong> %s</p>
                <p><strong>Order ID:</strong> <code>%s</code></p>
                <p><strong>Payable Amount:</strong> ₹%s</p>
                <p><strong>Deliver to:</strong> %s</p>
                %s
                <div style="display:flex;flex-direction:column;gap:10px;margin-top:16px;">
                  %s
                  %s
                </div>
                <p style="margin-top:14px;color:#94a3b8;font-size:13px;">
                  UPI ID: <code>%s</code>
                </p>
                """.formatted(
                HtmlUtils.htmlEscape(props.getStoreName()),
                HtmlUtils.htmlEscape(order.getOrderCode()),
                HtmlUtils.htmlEscape(amount),
                HtmlUtils.htmlEscape(order.getDeliveryAddress()),
                itemsHtml.toString(),
                upiLink.isBlank() ? "" : "<a href=\"" + HtmlUtils.htmlEscape(upiLink)
                        + "\" style=\"display:block;text-align:center;background:#0284c7;color:#fff;padding:11px 14px;border-radius:10px;font-weight:700;text-decoration:none;\">Pay ₹"
                        + HtmlUtils.htmlEscape(amount) + " in UPI App</a>",
                showDemoButton ? """
                        <form method="post" action="/api/payments/checkout/confirm" style="margin:0;">
                          <input type="hidden" name="orderCode" value="%s" />
                          <button type="submit" style="width:100%%;background:#059669;color:#fff;border:none;padding:11px 14px;border-radius:10px;font-weight:700;font-size:14px;cursor:pointer;">
                            Simulate UPI Paid (Demo Mode)
                          </button>
                        </form>
                        """.formatted(HtmlUtils.htmlEscape(order.getOrderCode())) : "",
                HtmlUtils.htmlEscape(props.getMerchantUpiId())
        );

        return ResponseEntity.ok(renderPage("UPI Checkout", body, false));
    }

    @PostMapping(value = "/checkout/confirm", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> confirmDemoCheckout(@RequestParam(name = "orderCode") String orderCode) {
        adminAuthGuard.requireSimulatePayAllowed();
        adminAuthGuard.requireAdminIfConfigured(null);

        try {
            BotOrder paid = engine.markPaymentSucceeded(orderCode, "pay_checkout_demo_" + System.currentTimeMillis());
            return handleRazorpayCallback(paid.getOrderCode(), paid.getRazorpayPaymentId(), "paid");
        } catch (IllegalStateException ex) {
            return ResponseEntity.ok(renderPage("Order Cannot Be Paid",
                    HtmlUtils.htmlEscape(ex.getMessage()), false));
        }
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


package com.drc.whatsappbot.service;

import com.drc.whatsappbot.config.BotProperties;
import com.drc.whatsappbot.model.BotOrder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class RazorpayPaymentLinkService {

    private static final Logger log = LoggerFactory.getLogger(RazorpayPaymentLinkService.class);

    public record PaymentLinkOutcome(
            String linkId,
            String shortUrl,
            String upiDeepLink,
            boolean liveRazorpay) {
    }

    private final BotProperties props;
    private final ObjectMapper mapper;
    private final RestClient restClient;

    @Autowired
    public RazorpayPaymentLinkService(BotProperties props, ObjectMapper mapper) {
        this(props, mapper, RestClient.builder().baseUrl("https://api.razorpay.com/v1").build());
    }

    public RazorpayPaymentLinkService(BotProperties props, ObjectMapper mapper, RestClient restClient) {
        this.props = props;
        this.mapper = mapper;
        this.restClient = restClient;
    }

    public boolean isLiveConfigured() {
        return props.getRazorpay().getKeyId() != null && !props.getRazorpay().getKeyId().isBlank()
                && props.getRazorpay().getKeySecret() != null && !props.getRazorpay().getKeySecret().isBlank();
    }

    public PaymentLinkOutcome createPaymentLink(BotOrder order) {
        String upiUri = buildUpiIntentUri(order);

        if (isLiveConfigured()) {
            try {
                long amountPaise = order.getTotal()
                        .multiply(BigDecimal.valueOf(100))
                        .setScale(0, RoundingMode.HALF_UP)
                        .longValueExact();

                String basicToken = Base64.getEncoder().encodeToString(
                        (props.getRazorpay().getKeyId() + ":" + props.getRazorpay().getKeySecret())
                                .getBytes(StandardCharsets.UTF_8));

                Map<String, Object> customer = new LinkedHashMap<>();
                customer.put("name", order.getCustomerName() == null ? "WhatsApp Customer" : order.getCustomerName());
                customer.put("contact", order.getPhone());

                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("amount", amountPaise);
                payload.put("currency", "INR");
                payload.put("reference_id", order.getOrderCode());
                payload.put("description", props.getStoreName() + " Order " + order.getOrderCode());
                payload.put("customer", customer);
                payload.put("upi_link", true);
                payload.put("notify", Map.of("sms", false, "email", false));
                payload.put("callback_url", props.getRazorpay().getCallbackBaseUrl()
                        + "/api/payments/callback?orderCode=" + urlEncode(order.getOrderCode()));
                payload.put("callback_method", "get");

                JsonNode resp = restClient.post()
                        .uri("/payment_links")
                        .header("Authorization", "Basic " + basicToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(payload)
                        .retrieve()
                        .body(JsonNode.class);

                if (resp != null && resp.hasNonNull("id") && resp.hasNonNull("short_url")) {
                    return new PaymentLinkOutcome(
                            resp.get("id").asText(),
                            resp.get("short_url").asText(),
                            upiUri,
                            true);
                }
            } catch (Exception ex) {
                log.warn("Razorpay live payment link creation failed for {}, falling back to demo UPI link: {}",
                        order.getOrderCode(), ex.getMessage());
            }
        }

        String demoLinkId = "plink_demo_" + order.getOrderCode().replaceAll("[^A-Za-z0-9]", "");
        String demoShortUrl = "https://rzp.io/i/" + order.getOrderCode().toLowerCase();
        return new PaymentLinkOutcome(demoLinkId, demoShortUrl, upiUri, false);
    }

    public boolean verifyWebhookSignature(String rawBody, String signatureHeader) {
        String secret = props.getRazorpay().getWebhookSecret();
        if (secret == null || secret.isBlank()) {
            return !props.getSecurity().isStrictWebhookVerification();
        }
        if (signatureHeader == null || signatureHeader.isBlank()) {
            return false;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String expected = HexFormat.of().formatHex(mac.doFinal(rawBody.getBytes(StandardCharsets.UTF_8)));
            return MessageDigest.isEqual(
                    expected.getBytes(StandardCharsets.UTF_8),
                    signatureHeader.trim().getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            return false;
        }
    }

    public JsonNode parseWebhookPayload(String rawBody) throws Exception {
        return mapper.readTree(rawBody);
    }

    private String buildUpiIntentUri(BotOrder order) {
        String pa = urlEncode(props.getMerchantUpiId());
        String pn = urlEncode(props.getStoreName());
        String tr = urlEncode(order.getOrderCode());
        String tn = urlEncode("DRC Order " + order.getOrderCode());
        String am = order.getTotal().setScale(2, RoundingMode.HALF_UP).toPlainString();
        return "upi://pay?pa=" + pa + "&pn=" + pn + "&tr=" + tr + "&tn=" + tn + "&am=" + am + "&cu=INR";
    }

    private String urlEncode(String val) {
        return URLEncoder.encode(val == null ? "" : val, StandardCharsets.UTF_8);
    }
}

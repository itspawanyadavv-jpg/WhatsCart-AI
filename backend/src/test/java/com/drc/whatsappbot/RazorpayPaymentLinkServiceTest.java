package com.drc.whatsappbot;

import com.drc.whatsappbot.config.BotProperties;
import com.drc.whatsappbot.model.BotOrder;
import com.drc.whatsappbot.service.RazorpayPaymentLinkService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class RazorpayPaymentLinkServiceTest {

    @Test
    void buildsDemoPaymentLinkAndUpiUriWhenNotLiveConfigured() {
        BotProperties props = new BotProperties();
        RazorpayPaymentLinkService service = new RazorpayPaymentLinkService(props, new ObjectMapper());

        BotOrder order = sampleOrder("DRC-WA-4242", "371.00");
        var outcome = service.createPaymentLink(order);

        assertThat(outcome.liveRazorpay()).isFalse();
        assertThat(outcome.linkId()).isEqualTo("plink_demo_DRCWA4242");
        assertThat(outcome.shortUrl()).isEqualTo("https://rzp.io/i/drc-wa-4242");
        assertThat(outcome.upiDeepLink())
                .startsWith("upi://pay?")
                .contains("pa=drcgenstore%40okicici")
                .contains("tr=DRC-WA-4242")
                .contains("am=371.00")
                .contains("cu=INR");
    }

    @Test
    void createsLiveRazorpayLinkWhenKeysAreConfigured() {
        BotProperties props = new BotProperties();
        props.getRazorpay().setKeyId("rzp_test_123");
        props.getRazorpay().setKeySecret("secret_xyz");
        props.getRazorpay().setCallbackBaseUrl("https://bot.example.com");

        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.razorpay.com/v1");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://api.razorpay.com/v1/payment_links"))
                .andExpect(method(org.springframework.http.HttpMethod.POST))
                .andExpect(header("Authorization", org.hamcrest.Matchers.startsWith("Basic ")))
                .andRespond(withSuccess("{\"id\":\"plink_live_999\",\"short_url\":\"https://rzp.io/i/live999\"}",
                        MediaType.APPLICATION_JSON));

        RazorpayPaymentLinkService service = new RazorpayPaymentLinkService(props, new ObjectMapper(), builder.build());
        var outcome = service.createPaymentLink(sampleOrder("DRC-WA-8888", "250.00"));

        server.verify();
        assertThat(outcome.liveRazorpay()).isTrue();
        assertThat(outcome.linkId()).isEqualTo("plink_live_999");
        assertThat(outcome.shortUrl()).isEqualTo("https://rzp.io/i/live999");
    }

    @Test
    void verifiesWebhookSignaturesInPermissiveAndStrictModes() throws Exception {
        BotProperties props = new BotProperties();
        RazorpayPaymentLinkService service = new RazorpayPaymentLinkService(props, new ObjectMapper());

        // Non-strict without secret allows local dev
        assertThat(service.verifyWebhookSignature("{\"event\":\"payment_link.paid\"}", null)).isTrue();

        // Strict mode without secret rejects
        props.getSecurity().setStrictWebhookVerification(true);
        assertThat(service.verifyWebhookSignature("{\"event\":\"payment_link.paid\"}", null)).isFalse();

        // With webhook secret, validates HMAC-SHA256
        props.getRazorpay().setWebhookSecret("whsec_test_123");
        String body = "{\"event\":\"payment_link.paid\"}";
        String validSig = hmacSha256Hex("whsec_test_123", body);

        assertThat(service.verifyWebhookSignature(body, validSig)).isTrue();
        assertThat(service.verifyWebhookSignature(body, "bad_sig")).isFalse();
        assertThat(service.verifyWebhookSignature(body, null)).isFalse();
    }

    private BotOrder sampleOrder(String code, String total) {
        BotOrder order = new BotOrder();
        order.setOrderCode(code);
        order.setCustomerName("Aman");
        order.setPhone("+919876543210");
        order.setTotal(new BigDecimal(total));
        return order;
    }

    private String hmacSha256Hex(String secret, String payload) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
    }
}


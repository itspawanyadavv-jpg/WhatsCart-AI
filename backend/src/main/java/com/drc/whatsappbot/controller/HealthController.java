package com.drc.whatsappbot.controller;

import com.drc.whatsappbot.config.BotProperties;
import com.drc.whatsappbot.service.DrcCatalogSyncService;
import com.drc.whatsappbot.service.RazorpayPaymentLinkService;
import com.drc.whatsappbot.service.WhatsAppGatewayService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.HtmlUtils;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

@RestController
public class HealthController {

    private final BotProperties props;
    private final DrcCatalogSyncService catalogService;
    private final RazorpayPaymentLinkService paymentLinkService;
    private final WhatsAppGatewayService gatewayService;

    public HealthController(BotProperties props,
                            DrcCatalogSyncService catalogService,
                            RazorpayPaymentLinkService paymentLinkService,
                            WhatsAppGatewayService gatewayService) {
        this.props = props;
        this.catalogService = catalogService;
        this.paymentLinkService = paymentLinkService;
        this.gatewayService = gatewayService;
    }

    @GetMapping(value = "/", produces = MediaType.TEXT_HTML_VALUE)
    public String rootStatus() {
        return """
                <!doctype html>
                <html lang="en">
                <head>
                  <meta charset="utf-8" />
                  <meta name="viewport" content="width=device-width,initial-scale=1" />
                  <title>%s - Bot API</title>
                  <style>
                    body { font-family: system-ui, -apple-system, sans-serif; background:#0b141a; color:#e2e8f0; display:grid; place-items:center; min-height:100vh; margin:0; padding:20px; }
                    .card { background:#111b21; border:1px solid rgba(255,255,255,.12); border-radius:16px; padding:24px; max-width:480px; width:100%%; }
                    h1 { margin:0 0 10px; font-size:20px; color:#5eead4; }
                    p { margin:8px 0; color:#cbd5e1; font-size:14px; line-height:1.5; }
                    a { color:#38bdf8; text-decoration:none; }
                    code { background:#1f2c33; padding:2px 6px; border-radius:6px; color:#5eead4; }
                  </style>
                </head>
                <body>
                  <div class="card">
                    <h1>%s — WhatsApp AI Bot API</h1>
                    <p>Status: <strong>UP</strong> &bull; Catalog SKUs: <code>%d</code> &bull; LLM: <code>%s</code></p>
                    <p><a href="/api/health">Health JSON</a> &bull; <a href="/swagger-ui.html">Swagger API Docs</a> &bull; <a href="/api/catalog">Catalog JSON</a></p>
                    <p style="font-size:12.5px;color:#94a3b8;">Webhooks: <code>/api/webhooks/twilio</code> &bull; <code>/api/webhooks/whatsapp</code> &bull; <code>/api/webhooks/razorpay</code></p>
                  </div>
                </body>
                </html>
                """.formatted(
                HtmlUtils.htmlEscape(props.getStoreName()),
                HtmlUtils.htmlEscape(props.getStoreName()),
                catalogService.getAllProducts().size(),
                HtmlUtils.htmlEscape(resolveActiveLlmMode())
        );
    }

    @GetMapping("/api/health")
    public Map<String, Object> health() {
        String llmMode = resolveActiveLlmMode();

        Map<String, Object> map = new LinkedHashMap<>();
        map.put("status", "UP");
        map.put("storeName", props.getStoreName());
        map.put("drcApiBaseUrl", props.getDrc().getApiBaseUrl());
        map.put("drcLiveSynced", catalogService.isLastLiveSyncOk());
        map.put("catalogCount", catalogService.getAllProducts().size());
        map.put("llmMode", llmMode);
        map.put("whatsappMetaConfigured", gatewayService.isMetaConfigured());
        map.put("whatsappTwilioConfigured", gatewayService.isTwilioConfigured());
        map.put("razorpayLiveConfigured", paymentLinkService.isLiveConfigured());
        map.put("allowSimulatePay", props.getSecurity().isAllowSimulatePay());
        map.put("strictWebhookVerification", props.getSecurity().isStrictWebhookVerification());
        return map;
    }

    private String resolveActiveLlmMode() {
        String provider = props.getLlm().getProvider() == null
                ? "AUTO"
                : props.getLlm().getProvider().trim().toUpperCase(Locale.ROOT);
        boolean hasGemini = props.getLlm().getGeminiApiKey() != null && !props.getLlm().getGeminiApiKey().isBlank();
        boolean hasOpenAi = props.getLlm().getOpenaiApiKey() != null && !props.getLlm().getOpenaiApiKey().isBlank();

        return switch (provider) {
            case "FALLBACK" -> "HINGLISH_NLP_FALLBACK";
            case "OPENAI" -> hasOpenAi ? "OPENAI (" + props.getLlm().getOpenaiModel() + ")" : "HINGLISH_NLP_FALLBACK";
            case "GEMINI" -> hasGemini ? "GEMINI (" + props.getLlm().getGeminiModel() + ")" : "HINGLISH_NLP_FALLBACK";
            default -> {
                if (hasGemini) yield "GEMINI (" + props.getLlm().getGeminiModel() + ")";
                if (hasOpenAi) yield "OPENAI (" + props.getLlm().getOpenaiModel() + ")";
                yield "HINGLISH_NLP_FALLBACK";
            }
        };
    }
}


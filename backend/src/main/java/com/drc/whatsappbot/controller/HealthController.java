package com.drc.whatsappbot.controller;

import com.drc.whatsappbot.config.BotProperties;
import com.drc.whatsappbot.service.DrcCatalogSyncService;
import com.drc.whatsappbot.service.RazorpayPaymentLinkService;
import com.drc.whatsappbot.service.WhatsAppGatewayService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/health")
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

    @GetMapping
    public Map<String, Object> health() {
        String llmMode = "HINGLISH_NLP_FALLBACK";
        if (props.getLlm().getGeminiApiKey() != null && !props.getLlm().getGeminiApiKey().isBlank()) {
            llmMode = "GEMINI (" + props.getLlm().getGeminiModel() + ")";
        } else if (props.getLlm().getOpenaiApiKey() != null && !props.getLlm().getOpenaiApiKey().isBlank()) {
            llmMode = "OPENAI (" + props.getLlm().getOpenaiModel() + ")";
        }

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
}


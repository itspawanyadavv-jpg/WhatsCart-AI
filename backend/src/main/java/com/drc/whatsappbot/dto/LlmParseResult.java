package com.drc.whatsappbot.dto;

import com.drc.whatsappbot.enums.IntentType;

import java.util.List;

public record LlmParseResult(
        IntentType intent,
        List<ParsedItem> items,
        String deliveryAddress,
        String parserUsed,
        long latencyMs,
        String normalizedSummary
) {}


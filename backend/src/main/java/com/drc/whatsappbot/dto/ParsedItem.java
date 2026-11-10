package com.drc.whatsappbot.dto;

public record ParsedItem(
        String rawText,
        String productQuery,
        int quantity,
        String unit,
        String packSizeMention
) {}


package com.drc.whatsappbot.dto;

import java.math.BigDecimal;
import java.util.List;

public record CatalogMatchResult(
        String rawQuery,
        int requestedQty,
        boolean matched,
        boolean outOfStock,
        Long productId,
        String matchedProductName,
        BigDecimal unitPrice,
        String unit,
        int availableStock,
        double confidence,
        String matchReason,
        List<String> alternatives
) {}


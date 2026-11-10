package com.drc.whatsappbot.dto;

import java.math.BigDecimal;

public record CartItemEntry(
        Long productId,
        String productName,
        String unit,
        BigDecimal unitPrice,
        int quantity,
        String rawQuery,
        double confidence
) {
    public BigDecimal lineTotal() {
        return unitPrice.multiply(BigDecimal.valueOf(quantity));
    }
}


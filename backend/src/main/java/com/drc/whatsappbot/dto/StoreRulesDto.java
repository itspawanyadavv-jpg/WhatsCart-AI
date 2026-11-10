package com.drc.whatsappbot.dto;

import java.math.BigDecimal;

public record StoreRulesDto(
        double deliveryRadiusKm,
        BigDecimal minOrderValue,
        BigDecimal deliveryFee,
        BigDecimal freeDeliveryAbove,
        boolean liveSynced
) {}


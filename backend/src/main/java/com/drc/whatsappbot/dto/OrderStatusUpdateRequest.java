package com.drc.whatsappbot.dto;

import com.drc.whatsappbot.enums.BotOrderStatus;
import jakarta.validation.constraints.NotNull;

public record OrderStatusUpdateRequest(
        @NotNull BotOrderStatus status,
        String riderName,
        String otp
) {}


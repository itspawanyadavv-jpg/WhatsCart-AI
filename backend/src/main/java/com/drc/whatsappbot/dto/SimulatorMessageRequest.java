package com.drc.whatsappbot.dto;

import jakarta.validation.constraints.NotBlank;

public record SimulatorMessageRequest(
        @NotBlank String phone,
        String customerName,
        @NotBlank String message
) {}


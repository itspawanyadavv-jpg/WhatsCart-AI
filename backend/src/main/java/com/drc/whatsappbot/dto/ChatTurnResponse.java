package com.drc.whatsappbot.dto;

import com.drc.whatsappbot.enums.ConversationState;
import com.drc.whatsappbot.model.BotOrder;
import com.drc.whatsappbot.model.ChatMessage;

import java.math.BigDecimal;
import java.util.List;

public record ChatTurnResponse(
        String phone,
        String customerName,
        ConversationState state,
        String deliveryAddress,
        List<CartItemEntry> cart,
        BigDecimal subtotal,
        BigDecimal deliveryFee,
        BigDecimal total,
        BigDecimal amountForFreeDelivery,
        StoreRulesDto storeRules,
        LlmParseResult parseResult,
        List<CatalogMatchResult> matchResults,
        List<ChatMessage> messages,
        BotOrder activeOrder
) {}


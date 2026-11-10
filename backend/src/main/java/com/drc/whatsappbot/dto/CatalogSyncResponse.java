package com.drc.whatsappbot.dto;

import java.time.LocalDateTime;

public record CatalogSyncResponse(
        boolean liveSyncSucceeded,
        String drcApiBaseUrl,
        int liveDrcCount,
        int localSupplementCount,
        int totalCatalogCount,
        StoreRulesDto storeRules,
        LocalDateTime syncedAt,
        String message
) {}


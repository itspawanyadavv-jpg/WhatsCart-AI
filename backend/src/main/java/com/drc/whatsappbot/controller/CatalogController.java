package com.drc.whatsappbot.controller;

import com.drc.whatsappbot.config.AdminAuthGuard;
import com.drc.whatsappbot.config.BotProperties;
import com.drc.whatsappbot.dto.CatalogSyncResponse;
import com.drc.whatsappbot.service.DrcCatalogSyncService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/catalog")
public class CatalogController {

    private final DrcCatalogSyncService catalogService;
    private final BotProperties props;
    private final AdminAuthGuard adminAuthGuard;

    public CatalogController(DrcCatalogSyncService catalogService,
                             BotProperties props,
                             AdminAuthGuard adminAuthGuard) {
        this.catalogService = catalogService;
        this.props = props;
        this.adminAuthGuard = adminAuthGuard;
    }

    @GetMapping
    public Map<String, Object> getCatalog() {
        return Map.of(
                "products", catalogService.getAllProducts(),
                "storeRules", catalogService.getStoreRules(),
                "drcApiBaseUrl", props.getDrc().getApiBaseUrl(),
                "lastLiveSyncOk", catalogService.isLastLiveSyncOk(),
                "lastSyncedAt", catalogService.getLastSyncedAt() != null ? catalogService.getLastSyncedAt().toString() : ""
        );
    }

    @PostMapping("/sync")
    public CatalogSyncResponse sync(@RequestParam(defaultValue = "true") boolean includeLocalStaples,
                                    @RequestHeader(value = "X-Admin-Key", required = false) String adminKey) {
        adminAuthGuard.requireAdminIfConfigured(adminKey);
        return catalogService.syncCatalog(includeLocalStaples);
    }
}


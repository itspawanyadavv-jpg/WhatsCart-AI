package com.drc.whatsappbot.config;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Component
public class AdminAuthGuard {

    private final BotProperties props;

    public AdminAuthGuard(BotProperties props) {
        this.props = props;
    }

    public void requireAdminIfConfigured(String providedKey) {
        String configuredKey = props.getSecurity().getAdminApiKey();
        if (configuredKey == null || configuredKey.isBlank()) {
            return;
        }
        if (providedKey == null || !MessageDigest.isEqual(
                configuredKey.trim().getBytes(StandardCharsets.UTF_8),
                providedKey.trim().getBytes(StandardCharsets.UTF_8))) {
            throw new SecurityException("Missing or invalid X-Admin-Key header");
        }
    }

    public void requireSimulatePayAllowed() {
        if (!props.getSecurity().isAllowSimulatePay()) {
            throw new SecurityException("Demo payment simulation is disabled in this environment");
        }
    }
}


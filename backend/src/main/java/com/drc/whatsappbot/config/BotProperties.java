package com.drc.whatsappbot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "bot")
public class BotProperties {

    private String storeName = "DRC Krishna Gen Store";
    private String merchantUpiId = "drcgenstore@okicici";
    private List<String> corsAllowedOrigins = List.of("http://localhost:5175", "http://localhost:5173", "http://localhost:3000");
    private Drc drc = new Drc();
    private Llm llm = new Llm();
    private WhatsApp whatsapp = new WhatsApp();
    private Razorpay razorpay = new Razorpay();
    private Security security = new Security();

    public String getStoreName() { return storeName; }
    public void setStoreName(String storeName) { this.storeName = storeName; }
    public String getMerchantUpiId() { return merchantUpiId; }
    public void setMerchantUpiId(String merchantUpiId) { this.merchantUpiId = merchantUpiId; }
    public List<String> getCorsAllowedOrigins() { return corsAllowedOrigins; }
    public void setCorsAllowedOrigins(List<String> corsAllowedOrigins) { this.corsAllowedOrigins = corsAllowedOrigins; }
    public Drc getDrc() { return drc; }
    public void setDrc(Drc drc) { this.drc = drc; }
    public Llm getLlm() { return llm; }
    public void setLlm(Llm llm) { this.llm = llm; }
    public WhatsApp getWhatsapp() { return whatsapp; }
    public void setWhatsapp(WhatsApp whatsapp) { this.whatsapp = whatsapp; }
    public Razorpay getRazorpay() { return razorpay; }
    public void setRazorpay(Razorpay razorpay) { this.razorpay = razorpay; }
    public Security getSecurity() { return security; }
    public void setSecurity(Security security) { this.security = security; }

    public static class Drc {
        private String apiBaseUrl = "https://13-206-156-137.sslip.io";
        private boolean syncOnStartup = true;
        private boolean includeLocalStaples = true;
        private int timeoutMs = 3500;

        public String getApiBaseUrl() { return apiBaseUrl; }
        public void setApiBaseUrl(String apiBaseUrl) { this.apiBaseUrl = apiBaseUrl; }
        public boolean isSyncOnStartup() { return syncOnStartup; }
        public void setSyncOnStartup(boolean syncOnStartup) { this.syncOnStartup = syncOnStartup; }
        public boolean isIncludeLocalStaples() { return includeLocalStaples; }
        public void setIncludeLocalStaples(boolean includeLocalStaples) { this.includeLocalStaples = includeLocalStaples; }
        public int getTimeoutMs() { return timeoutMs; }
        public void setTimeoutMs(int timeoutMs) { this.timeoutMs = timeoutMs; }
    }

    public static class Llm {
        private String provider = "AUTO";
        private String geminiApiKey = "";
        private String geminiModel = "gemini-2.5-flash";
        private String openaiApiKey = "";
        private String openaiModel = "gpt-4o-mini";
        private int timeoutMs = 12000;

        public String getProvider() { return provider; }
        public void setProvider(String provider) { this.provider = provider; }
        public String getGeminiApiKey() { return geminiApiKey; }
        public void setGeminiApiKey(String geminiApiKey) { this.geminiApiKey = geminiApiKey; }
        public String getGeminiModel() { return geminiModel; }
        public void setGeminiModel(String geminiModel) { this.geminiModel = geminiModel; }
        public String getOpenaiApiKey() { return openaiApiKey; }
        public void setOpenaiApiKey(String openaiApiKey) { this.openaiApiKey = openaiApiKey; }
        public String getOpenaiModel() { return openaiModel; }
        public void setOpenaiModel(String openaiModel) { this.openaiModel = openaiModel; }
        public int getTimeoutMs() { return timeoutMs; }
        public void setTimeoutMs(int timeoutMs) { this.timeoutMs = timeoutMs; }
    }

    public static class WhatsApp {
        private String provider = "SIMULATOR";
        private String verifyToken = "drc-whatsapp-verify-token";
        private String appSecret = "";
        private String accessToken = "";
        private String phoneNumberId = "";
        private String twilioAccountSid = "";
        private String twilioAuthToken = "";
        private String twilioFromNumber = "";

        public String getProvider() { return provider; }
        public void setProvider(String provider) { this.provider = provider; }
        public String getVerifyToken() { return verifyToken; }
        public void setVerifyToken(String verifyToken) { this.verifyToken = verifyToken; }
        public String getAppSecret() { return appSecret; }
        public void setAppSecret(String appSecret) { this.appSecret = appSecret; }
        public String getAccessToken() { return accessToken; }
        public void setAccessToken(String accessToken) { this.accessToken = accessToken; }
        public String getPhoneNumberId() { return phoneNumberId; }
        public void setPhoneNumberId(String phoneNumberId) { this.phoneNumberId = phoneNumberId; }
        public String getTwilioAccountSid() { return twilioAccountSid; }
        public void setTwilioAccountSid(String twilioAccountSid) { this.twilioAccountSid = twilioAccountSid; }
        public String getTwilioAuthToken() { return twilioAuthToken; }
        public void setTwilioAuthToken(String twilioAuthToken) { this.twilioAuthToken = twilioAuthToken; }
        public String getTwilioFromNumber() { return twilioFromNumber; }
        public void setTwilioFromNumber(String twilioFromNumber) { this.twilioFromNumber = twilioFromNumber; }
    }

    public static class Razorpay {
        private String keyId = "";
        private String keySecret = "";
        private String webhookSecret = "";
        private String callbackBaseUrl = "http://localhost:8090";

        public String getKeyId() { return keyId; }
        public void setKeyId(String keyId) { this.keyId = keyId; }
        public String getKeySecret() { return keySecret; }
        public void setKeySecret(String keySecret) { this.keySecret = keySecret; }
        public String getWebhookSecret() { return webhookSecret; }
        public void setWebhookSecret(String webhookSecret) { this.webhookSecret = webhookSecret; }
        public String getCallbackBaseUrl() { return callbackBaseUrl; }
        public void setCallbackBaseUrl(String callbackBaseUrl) { this.callbackBaseUrl = callbackBaseUrl; }
    }

    public static class Security {
        private boolean strictWebhookVerification = false;
        private boolean allowSimulatePay = true;
        private String adminApiKey = "";

        public boolean isStrictWebhookVerification() { return strictWebhookVerification; }
        public void setStrictWebhookVerification(boolean strictWebhookVerification) { this.strictWebhookVerification = strictWebhookVerification; }
        public boolean isAllowSimulatePay() { return allowSimulatePay; }
        public void setAllowSimulatePay(boolean allowSimulatePay) { this.allowSimulatePay = allowSimulatePay; }
        public String getAdminApiKey() { return adminApiKey; }
        public void setAdminApiKey(String adminApiKey) { this.adminApiKey = adminApiKey; }
    }
}


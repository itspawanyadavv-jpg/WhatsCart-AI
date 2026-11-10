package com.drc.whatsappbot.service;

import com.drc.whatsappbot.config.BotProperties;
import com.drc.whatsappbot.dto.LlmParseResult;
import com.drc.whatsappbot.dto.ParsedItem;
import com.drc.whatsappbot.enums.ConversationState;
import com.drc.whatsappbot.enums.IntentType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class LlmHinglishParserService {

    private static final Logger log = LoggerFactory.getLogger(LlmHinglishParserService.class);

    private static final Map<String, Integer> HINDI_NUMBER_WORDS = Map.ofEntries(
            Map.entry("ek", 1),
            Map.entry("one", 1),
            Map.entry("a", 1),
            Map.entry("do", 2),
            Map.entry("two", 2),
            Map.entry("teen", 3),
            Map.entry("three", 3),
            Map.entry("char", 4),
            Map.entry("chaar", 4),
            Map.entry("four", 4),
            Map.entry("paanch", 5),
            Map.entry("panch", 5),
            Map.entry("five", 5),
            Map.entry("chhe", 6),
            Map.entry("che", 6),
            Map.entry("six", 6),
            Map.entry("saat", 7),
            Map.entry("seven", 7),
            Map.entry("aath", 8),
            Map.entry("eight", 8),
            Map.entry("nau", 9),
            Map.entry("nine", 9),
            Map.entry("das", 10),
            Map.entry("ten", 10)
    );

    private static final Pattern MULTIPLIER_PACK_PATTERN = Pattern.compile(
            "^(\\d+)\\s*(?:x|\\*)\\s*(\\d+(?:\\.\\d+)?)\\s*(kg|kgs|g|gm|gms|grams|l|ltr|litre|litres|ml)\\b(.*)$",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern LEADING_WEIGHT_PATTERN = Pattern.compile(
            "^(\\d+(?:\\.\\d+)?)\\s*(kg|kgs|g|gm|gms|grams|l|ltr|litre|litres|ml)\\b(.*)$",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern LEADING_COUNT_UNIT_PATTERN = Pattern.compile(
            "^(\\d+)\\s+(?:(packet|packets|pkt|pkts|pack|packs|bottle|bottles|btl|box|boxes|bag|bags|pcs|pc|piece|pieces|tube|tubes|cup|cups|tin|can)\\b)?(.*)$",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern TRAILING_QTY_PATTERN = Pattern.compile(
            "^(.*?)\\s+(\\d+)\\s*(kg|kgs|g|gm|gms|l|ltr|ml|packet|packets|pkt|pack|bottle|pcs)?$",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern UPDATE_QTY_PREFIX_PATTERN = Pattern.compile(
            "^(?:make|set|change|update)\\s+(.+?)(?:\\s+(?:to|=)\\s*|\\s+)(\\d+|ek|do|teen|char|paanch|panch|chhe|che|saat|aath|nau|das)(?:\\s+(?:packet|packets|pkt|pack|packs|pcs|bottle|bottles|bag|bags|box|boxes))?$",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern UPDATE_QTY_HINGLISH_PATTERN = Pattern.compile(
            "^(.+?)\\s+(?:ko\\s+)?(\\d+|ek|do|teen|char|paanch|panch|chhe|che|saat|aath|nau|das)\\s+(?:kar do|kardo|karo)$",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern INLINE_ADDRESS_PATTERN = Pattern.compile(
            "(?:deliver\\s+(?:at|to|on)|address\\s*[:\\-]|bhejo\\s+(?:at|to|par|pe)|par\\s+bhejo|pe\\s+bhejo)\\s+(.+)$",
            Pattern.CASE_INSENSITIVE
    );

    private final BotProperties props;
    private final ObjectMapper mapper;
    private final RestClient llmClient;
    private final AtomicLong geminiCooldownUntilMs = new AtomicLong(0L);
    private final AtomicLong openAiCooldownUntilMs = new AtomicLong(0L);
    private static final long AUTH_FAILURE_COOLDOWN_MS = 5 * 60 * 1000L;

    @Autowired
    public LlmHinglishParserService(BotProperties props, ObjectMapper mapper) {
        this(props, mapper, buildDefaultRestClient(props));
    }

    public LlmHinglishParserService(BotProperties props, ObjectMapper mapper, RestClient llmClient) {
        this.props = props;
        this.mapper = mapper;
        this.llmClient = llmClient;
    }

    private static RestClient buildDefaultRestClient(BotProperties props) {
        SimpleClientHttpRequestFactory rf = new SimpleClientHttpRequestFactory();
        rf.setConnectTimeout(props.getLlm().getTimeoutMs());
        rf.setReadTimeout(props.getLlm().getTimeoutMs());
        return RestClient.builder().requestFactory(rf).build();
    }

    public LlmParseResult parse(String message, ConversationState currentState) {
        return parse(message, currentState, "", List.of());
    }

    public LlmParseResult parse(String message,
                                ConversationState currentState,
                                String cartSummary,
                                List<String> catalogHints) {
        long start = System.currentTimeMillis();
        String trimmed = message == null ? "" : message.trim();
        String providerPref = props.getLlm().getProvider() == null ? "AUTO" : props.getLlm().getProvider().toUpperCase(Locale.ROOT);

        boolean hasGemini = props.getLlm().getGeminiApiKey() != null && !props.getLlm().getGeminiApiKey().isBlank();
        boolean hasOpenAi = props.getLlm().getOpenaiApiKey() != null && !props.getLlm().getOpenaiApiKey().isBlank();

        long now = System.currentTimeMillis();
        if (("GEMINI".equals(providerPref) || "AUTO".equals(providerPref)) && hasGemini
                && now >= geminiCooldownUntilMs.get()) {
            try {
                return parseWithGemini(trimmed, currentState, cartSummary, catalogHints, start);
            } catch (HttpClientErrorException.Unauthorized | HttpClientErrorException.Forbidden ex) {
                geminiCooldownUntilMs.set(System.currentTimeMillis() + AUTH_FAILURE_COOLDOWN_MS);
                log.warn("Gemini auth failed ({}), cooling down for 5 min and falling back", ex.getStatusCode());
            } catch (Exception ex) {
                log.warn("Gemini LLM extraction failed, falling back: {}", ex.getMessage());
            }
        }

        if (("OPENAI".equals(providerPref) || "AUTO".equals(providerPref)) && hasOpenAi
                && now >= openAiCooldownUntilMs.get()) {
            try {
                return parseWithOpenAi(trimmed, currentState, cartSummary, catalogHints, start);
            } catch (HttpClientErrorException.Unauthorized | HttpClientErrorException.Forbidden ex) {
                openAiCooldownUntilMs.set(System.currentTimeMillis() + AUTH_FAILURE_COOLDOWN_MS);
                log.warn("OpenAI auth failed ({}), cooling down for 5 min and falling back", ex.getStatusCode());
            } catch (Exception ex) {
                log.warn("OpenAI LLM extraction failed, falling back: {}", ex.getMessage());
            }
        }

        return parseWithHinglishRules(trimmed, currentState, start);
    }

    public boolean isGeminiMultimodalReady() {
        String providerPref = props.getLlm().getProvider() == null ? "AUTO" : props.getLlm().getProvider().toUpperCase(Locale.ROOT);
        boolean hasGemini = props.getLlm().getGeminiApiKey() != null && !props.getLlm().getGeminiApiKey().isBlank();
        return ("GEMINI".equals(providerPref) || "AUTO".equals(providerPref))
                && hasGemini
                && System.currentTimeMillis() >= geminiCooldownUntilMs.get();
    }

    public Optional<LlmParseResult> parseMediaWithGemini(byte[] mediaBytes,
                                                       String mimeType,
                                                       String caption,
                                                       ConversationState state,
                                                       String cartSummary,
                                                       List<String> catalogHints) {
        if (!isGeminiMultimodalReady() || mediaBytes == null || mediaBytes.length == 0) {
            return Optional.empty();
        }
        long start = System.currentTimeMillis();
        try {
            String url = "https://generativelanguage.googleapis.com/v1beta/models/"
                    + props.getLlm().getGeminiModel()
                    + ":generateContent?key=" + props.getLlm().getGeminiApiKey();

            String prompt = buildSystemPrompt(state, cartSummary, catalogHints)
                    + "\n\nExtract the grocery order items from the attached customer WhatsApp media"
                    + (caption != null && !caption.isBlank() ? " (caption: \"" + caption + "\")" : "") + ".";

            String base64 = Base64.getEncoder().encodeToString(mediaBytes);
            Map<String, Object> body = Map.of(
                    "contents", List.of(Map.of(
                            "parts", List.of(
                                    Map.of("text", prompt),
                                    Map.of("inline_data", Map.of(
                                            "mime_type", mimeType == null || mimeType.isBlank() ? "image/jpeg" : mimeType,
                                            "data", base64
                                    ))
                            )
                    )),
                    "generationConfig", Map.of(
                            "temperature", 0.1,
                            "responseMimeType", "application/json"
                    )
            );

            String responseStr = llmClient.post()
                    .uri(url)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class);

            JsonNode root = mapper.readTree(responseStr);
            String jsonText = root.path("candidates").path(0).path("content").path("parts").path(0).path("text").asText();
            return Optional.of(toParseResult(jsonText, "GEMINI-MULTIMODAL (" + props.getLlm().getGeminiModel() + ")", start, caption));
        } catch (HttpClientErrorException.Unauthorized | HttpClientErrorException.Forbidden ex) {
            geminiCooldownUntilMs.set(System.currentTimeMillis() + AUTH_FAILURE_COOLDOWN_MS);
            log.warn("Gemini multimodal auth failed ({}), cooling down", ex.getStatusCode());
            return Optional.empty();
        } catch (Exception ex) {
            log.warn("Gemini multimodal extraction failed: {}", ex.getMessage());
            return Optional.empty();
        }
    }

    private LlmParseResult parseWithGemini(String message,
                                         ConversationState state,
                                         String cartSummary,
                                         List<String> catalogHints,
                                         long start) throws Exception {
        String url = "https://generativelanguage.googleapis.com/v1beta/models/"
                + props.getLlm().getGeminiModel()
                + ":generateContent?key=" + props.getLlm().getGeminiApiKey();

        Map<String, Object> body = Map.of(
                "contents", List.of(Map.of(
                        "parts", List.of(Map.of("text", buildSystemPrompt(state, cartSummary, catalogHints)
                                + "\n\nCustomer WhatsApp message: \"" + message + "\""))
                )),
                "generationConfig", Map.of(
                        "temperature", 0.1,
                        "responseMimeType", "application/json"
                )
        );

        String responseStr = llmClient.post()
                .uri(url)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(String.class);

        JsonNode root = mapper.readTree(responseStr);
        String jsonText = root.path("candidates").path(0).path("content").path("parts").path(0).path("text").asText();
        return toParseResult(jsonText, "GEMINI (" + props.getLlm().getGeminiModel() + ")", start, message);
    }

    private LlmParseResult parseWithOpenAi(String message,
                                         ConversationState state,
                                         String cartSummary,
                                         List<String> catalogHints,
                                         long start) throws Exception {
        Map<String, Object> body = Map.of(
                "model", props.getLlm().getOpenaiModel(),
                "temperature", 0.1,
                "response_format", Map.of("type", "json_object"),
                "messages", List.of(
                        Map.of("role", "system", "content", buildSystemPrompt(state, cartSummary, catalogHints)),
                        Map.of("role", "user", "content", message)
                )
        );

        String responseStr = llmClient.post()
                .uri("https://api.openai.com/v1/chat/completions")
                .header("Authorization", "Bearer " + props.getLlm().getOpenaiApiKey())
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(String.class);

        JsonNode root = mapper.readTree(responseStr);
        String jsonText = root.path("choices").path(0).path("message").path("content").asText();
        return toParseResult(jsonText, "OPENAI (" + props.getLlm().getOpenaiModel() + ")", start, message);
    }

    private LlmParseResult toParseResult(String jsonText, String parserUsed, long start, String originalMessage) throws Exception {
        String cleaned = cleanJsonFence(jsonText);
        JsonNode node = mapper.readTree(cleaned);

        IntentType intent = parseIntent(node.path("intent").asText("UNKNOWN"));
        List<ParsedItem> items = new ArrayList<>();
        JsonNode arr = node.path("items");
        if (arr.isArray()) {
            for (JsonNode itemNode : arr) {
                String query = itemNode.path("productQuery").asText("").trim();
                if (query.isEmpty()) continue;
                String raw = itemNode.path("rawText").asText(query);
                int qty = Math.max(1, itemNode.path("quantity").asInt(1));
                String unit = itemNode.path("unit").asText("pcs");
                String pack = itemNode.path("packSizeMention").isNull() ? null : itemNode.path("packSizeMention").asText(null);
                if (pack != null && pack.isBlank()) pack = null;
                items.add(new ParsedItem(raw, query, qty, unit, pack));
            }
        }
        String address = node.path("deliveryAddress").isNull() ? null : node.path("deliveryAddress").asText(null);
        if (address != null && address.isBlank()) address = null;
        String summary = node.path("normalizedSummary").asText("Parsed via " + parserUsed);

        return new LlmParseResult(intent, items, address, parserUsed, System.currentTimeMillis() - start, summary);
    }

    public LlmParseResult parseWithHinglishRules(String message, ConversationState currentState, long start) {
        String normalized = message.trim();
        String lower = normalized.toLowerCase(Locale.ROOT);

        if (lower.isBlank()) {
            return new LlmParseResult(IntentType.UNKNOWN, List.of(), null, "HINGLISH_NLP_FALLBACK", elapsed(start), "Empty message");
        }

        // 1. Check remove item commands first
        if (lower.startsWith("remove ") || lower.startsWith("hatao ") || lower.startsWith("delete ") || lower.contains("nahi chahiye")) {
            String target = lower
                    .replaceFirst("^(remove|hatao|delete)\\s+", "")
                    .replace("nahi chahiye", "")
                    .trim();
            List<ParsedItem> items = target.isBlank() ? List.of() : extractItemSegments(target);
            return new LlmParseResult(IntentType.REMOVE_ITEMS, items, null, "HINGLISH_NLP_FALLBACK", elapsed(start), "Remove item request");
        }

        // 1b. Check explicit cart quantity update commands (e.g. "make milk 3", "set bread to 2", "milk 3 kar do")
        Matcher updateMatch = UPDATE_QTY_PREFIX_PATTERN.matcher(normalized);
        if (!updateMatch.matches()) {
            updateMatch = UPDATE_QTY_HINGLISH_PATTERN.matcher(normalized);
        }
        if (updateMatch.matches()) {
            String itemQuery = cleanProductQuery(updateMatch.group(1));
            String qtyToken = updateMatch.group(2).toLowerCase(Locale.ROOT);
            int qty = HINDI_NUMBER_WORDS.getOrDefault(qtyToken, safeInt(qtyToken, 1));
            if (!itemQuery.isBlank() && !itemQuery.equalsIgnoreCase("order") && !itemQuery.equalsIgnoreCase("pack")) {
                ParsedItem item = new ParsedItem(normalized, itemQuery, qty, "pcs", null);
                return new LlmParseResult(IntentType.UPDATE_QUANTITY, List.of(item), null, "HINGLISH_NLP_FALLBACK",
                        elapsed(start), "Update quantity to " + qty + " for " + itemQuery);
            }
        }

        // 2. Cancel placed order vs clear cart
        if (matchesAny(lower, "^(cancel order|order cancel|cancel my order|order cancel karo|cancel last order)$")
                || ("cancel".equals(lower) && currentState == ConversationState.IDLE)) {
            return new LlmParseResult(IntentType.CANCEL_ORDER, List.of(), null, "HINGLISH_NLP_FALLBACK", elapsed(start), "Cancel active order command");
        }
        if (matchesAny(lower, "^(clear|reset|empty|cancel|hatao sab|clear cart)$")) {
            return new LlmParseResult(IntentType.CLEAR_CART, List.of(), null, "HINGLISH_NLP_FALLBACK", elapsed(start), "Clear cart command");
        }

        // 3. Payment mode commands
        if (matchesAny(lower, ".*\\b(upi|online|razorpay|pay online|gpay|phonepe|paytm|qr)\\b.*")
                && !containsGroceryKeywords(lower)) {
            return new LlmParseResult(IntentType.PAY_UPI, List.of(), null, "HINGLISH_NLP_FALLBACK", elapsed(start), "Customer selected UPI / Razorpay online payment");
        }
        if (matchesAny(lower, ".*\\b(cod|cash on delivery|cash|cash dunga|ghar pe dunga)\\b.*")
                && !containsGroceryKeywords(lower)) {
            return new LlmParseResult(IntentType.PAY_COD, List.of(), null, "HINGLISH_NLP_FALLBACK", elapsed(start), "Customer selected Cash on Delivery");
        }

        // 4. Reorder / order history / tracking status
        if (matchesAny(lower, ".*\\b(reorder|re-order|repeat order|repeat last order|wahi order|same order|phir se bhejo)\\b.*")) {
            return new LlmParseResult(IntentType.REORDER, List.of(), null, "HINGLISH_NLP_FALLBACK", elapsed(start), "Repeat last order request");
        }
        if (matchesAny(lower, ".*\\b(my orders|order history|previous orders|past orders|purane order|mere orders)\\b.*")) {
            return new LlmParseResult(IntentType.ORDER_HISTORY, List.of(), null, "HINGLISH_NLP_FALLBACK", elapsed(start), "View order history request");
        }
        if (matchesAny(lower, ".*\\b(status|track|kahan pahuncha|kaha pahucha|order status|mera order|drc-wa-\\d+)\\b.*")) {
            return new LlmParseResult(IntentType.CHECK_STATUS, List.of(), null, "HINGLISH_NLP_FALLBACK", elapsed(start), "Customer asked for order tracking status");
        }

        // 5. View cart / bill
        if (matchesAny(lower, "^(cart|view cart|bill|total|mera cart|kya add hua)$")) {
            return new LlmParseResult(IntentType.VIEW_CART, List.of(), null, "HINGLISH_NLP_FALLBACK", elapsed(start), "View cart command");
        }

        // 6. Browse catalog
        if (matchesAny(lower, ".*\\b(catalog|catalogue|menu|price list|kya kya hai|kya milta hai|items list|products)\\b.*")) {
            return new LlmParseResult(IntentType.BROWSE_CATALOG, List.of(), null, "HINGLISH_NLP_FALLBACK", elapsed(start), "Browse store catalog");
        }

        // 7. Help
        if (matchesAny(lower, "^(help|madad|commands|kaise order karu|how)$")) {
            return new LlmParseResult(IntentType.HELP, List.of(), null, "HINGLISH_NLP_FALLBACK", elapsed(start), "Help request");
        }

        // 8. Confirmation words when reviewing cart
        if (matchesAny(lower, "^(confirm|haan|ha|yes|ok|okay|done|checkout|place order|pack karo|theek hai|thik hai|proceed|order confirm)$")) {
            return new LlmParseResult(IntentType.CONFIRM_ORDER, List.of(), null, "HINGLISH_NLP_FALLBACK", elapsed(start), "Confirm checkout intent");
        }

        // 9. Pure greetings (only if no numbers/items present)
        if (matchesAny(lower, "^(hi|hello|hey|namaste|namaskar|hii|hlo|good morning|good evening|ram ram|jai shri krishna)[!.]*$")) {
            return new LlmParseResult(IntentType.GREETING, List.of(), null, "HINGLISH_NLP_FALLBACK", elapsed(start), "Greeting");
        }

        // 10. Check for explicit or contextual delivery address
        String inlineAddress = null;
        String itemText = normalized;
        Matcher addrMatcher = INLINE_ADDRESS_PATTERN.matcher(normalized);
        if (addrMatcher.find()) {
            inlineAddress = addrMatcher.group(1).trim();
            itemText = normalized.substring(0, addrMatcher.start()).trim();
        } else if (currentState == ConversationState.AWAITING_ADDRESS && !looksLikeGroceryOrder(lower)) {
            return new LlmParseResult(IntentType.SET_ADDRESS, List.of(), normalized, "HINGLISH_NLP_FALLBACK", elapsed(start), "Delivery address captured");
        } else if (looksLikePureAddress(lower)) {
            return new LlmParseResult(IntentType.SET_ADDRESS, List.of(), normalized, "HINGLISH_NLP_FALLBACK", elapsed(start), "Delivery address captured");
        }

        // 11. Extract Hinglish grocery items
        List<ParsedItem> items = extractItemSegments(itemText);
        if (!items.isEmpty()) {
            IntentType intent = (currentState == ConversationState.REVIEWING_CART || lower.startsWith("add ") || lower.contains("aur add"))
                    ? IntentType.ADD_ITEMS
                    : IntentType.ORDER;
            String summary = "Extracted " + items.size() + " grocery item(s) in Hinglish"
                    + (inlineAddress != null ? " with delivery address" : "");
            return new LlmParseResult(intent, items, inlineAddress, "HINGLISH_NLP_FALLBACK", elapsed(start), summary);
        }

        if (inlineAddress != null) {
            return new LlmParseResult(IntentType.SET_ADDRESS, List.of(), inlineAddress, "HINGLISH_NLP_FALLBACK", elapsed(start), "Delivery address captured");
        }

        return new LlmParseResult(IntentType.UNKNOWN, List.of(), null, "HINGLISH_NLP_FALLBACK", elapsed(start), "Could not identify grocery items or command");
    }

    List<ParsedItem> extractItemSegments(String rawText) {
        if (rawText == null || rawText.isBlank()) {
            return List.of();
        }

        // Strip common Hinglish conversational fillers
        String cleaned = rawText
                .replaceAll("(?i)\\b(hi|hello|namaste|bhaiya|bhai|didi|sir|madam|please|pls|plz|mujhe|mere liye|humko)\\b", " ")
                .replaceAll("(?i)\\b(deliver kar do|deliver kardo|bhej do|bhejdo|bhejo|send karo|la do|lado|de do|dedo|chahiye|chahie|order karna hai|order karo|add karo|mangwana hai|pack kar do|pack kardo)\\b", " ")
                .replaceAll("\\s+", " ")
                .trim();

        if (cleaned.isBlank()) {
            return List.of();
        }

        // Replace Hindi number words with digits when standalone
        cleaned = replaceHindiNumberWords(cleaned);

        // Split on commas, semicolons, newlines, '+', '&', or Hinglish conjunctions 'aur'/'and'
        String[] parts = cleaned.split("\\s*(?:,|;|\\n|\\+|&|\\baur\\b|\\band\\b)\\s*");

        List<ParsedItem> result = new ArrayList<>();
        for (String part : parts) {
            String segment = part.trim().replaceAll("^[\\-•*]+", "").trim();
            if (segment.isBlank() || segment.length() < 2) {
                continue;
            }
            ParsedItem parsed = parseSingleSegment(segment);
            if (parsed != null && !parsed.productQuery().isBlank()) {
                result.add(parsed);
            }
        }
        return result;
    }

    private ParsedItem parseSingleSegment(String segment) {
        // Case 1: "2 x 5kg Aashirvaad atta"
        Matcher mult = MULTIPLIER_PACK_PATTERN.matcher(segment);
        if (mult.matches()) {
            int qty = safeInt(mult.group(1), 1);
            String packSize = mult.group(2) + normalizeUnit(mult.group(3));
            String name = cleanProductQuery(mult.group(4));
            if (!name.isBlank()) {
                return new ParsedItem(segment, name, qty, normalizeUnit(mult.group(3)), packSize);
            }
        }

        // Case 2: "5kg Aashirvaad atta" or "500g Amul butter" or "1 L Fortune oil"
        Matcher weight = LEADING_WEIGHT_PATTERN.matcher(segment);
        if (weight.matches()) {
            String num = weight.group(1);
            String unit = normalizeUnit(weight.group(2));
            String packSize = num + unit;
            String name = cleanProductQuery(weight.group(3));
            if (!name.isBlank()) {
                return new ParsedItem(segment, name, 1, unit, packSize);
            }
        }

        // Case 3: "2 packet Amul milk" or "1 brown bread" or "3 Parle-G"
        Matcher count = LEADING_COUNT_UNIT_PATTERN.matcher(segment);
        if (count.matches()) {
            int qty = safeInt(count.group(1), 1);
            String unit = count.group(2) != null ? normalizeUnit(count.group(2)) : "pcs";
            String name = cleanProductQuery(count.group(3));
            // Check if name itself starts with a pack size like "5kg atta"
            Matcher innerWeight = LEADING_WEIGHT_PATTERN.matcher(name);
            if (innerWeight.matches()) {
                String packSize = innerWeight.group(1) + normalizeUnit(innerWeight.group(2));
                String innerName = cleanProductQuery(innerWeight.group(3));
                if (!innerName.isBlank()) {
                    return new ParsedItem(segment, innerName, qty, normalizeUnit(innerWeight.group(2)), packSize);
                }
            }
            if (!name.isBlank()) {
                return new ParsedItem(segment, name, qty, unit, null);
            }
        }

        // Case 4: Trailing qty like "Toor dal 2kg" or "Tata salt 2"
        Matcher trailing = TRAILING_QTY_PATTERN.matcher(segment);
        if (trailing.matches()) {
            String name = cleanProductQuery(trailing.group(1));
            int num = safeInt(trailing.group(2), 1);
            String rawUnit = trailing.group(3);
            String unit = rawUnit == null ? "pcs" : normalizeUnit(rawUnit);
            if (rawUnit != null && isWeightOrVolumeUnit(unit)) {
                return new ParsedItem(segment, name, 1, unit, num + unit);
            }
            return new ParsedItem(segment, name, num, unit, null);
        }

        // Case 5: Plain item name without explicit quantity -> qty = 1
        String cleanName = cleanProductQuery(segment);
        if (cleanName.isBlank()) return null;
        return new ParsedItem(segment, cleanName, 1, "pcs", null);
    }

    private String replaceHindiNumberWords(String input) {
        String[] tokens = input.split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < tokens.length; i++) {
            String lowerTok = tokens[i].toLowerCase(Locale.ROOT);
            // Only replace 'a' if it's followed by a word and not part of an item
            if ("a".equals(lowerTok) && i > 0) {
                sb.append(tokens[i]);
            } else if (HINDI_NUMBER_WORDS.containsKey(lowerTok)) {
                sb.append(HINDI_NUMBER_WORDS.get(lowerTok));
            } else {
                sb.append(tokens[i]);
            }
            if (i < tokens.length - 1) sb.append(' ');
        }
        return sb.toString();
    }

    private boolean looksLikeGroceryOrder(String lower) {
        return lower.matches(".*\\b(\\d+|ek|do|teen|char|paanch|kg|packet|milk|doodh|bread|atta|dal|rice|oil|salt|butter|chai|tea|soap|surf|maggi|bhujia|chips|biscuit|sugar|cheeni)\\b.*")
                && !looksLikePureAddress(lower);
    }

    private boolean looksLikePureAddress(String lower) {
        boolean hasAddressKeyword = lower.matches(".*\\b(flat|house|h\\.no|sector|block|gali|lane|road|nagar|colony|apartment|apts|floor|heights|enclave|vihar|pin|pincode)\\b.*");
        boolean hasGroceryWord = containsGroceryKeywords(lower);
        return hasAddressKeyword && !hasGroceryWord;
    }

    private boolean containsGroceryKeywords(String lower) {
        return lower.matches(".*\\b(milk|doodh|dudh|bread|atta|aata|dal|daal|rice|chawal|oil|tel|salt|namak|butter|makkhan|chai|tea|soap|sabun|surf|maggi|bhujia|chips|biscuit|parle|sugar|cheeni|curd|dahi|colgate|closeup|masala)\\b.*");
    }

    private String cleanProductQuery(String raw) {
        return raw == null ? "" : raw
                .replaceAll("(?i)\\b(of|ka|ki|ke|wala|wali|wale)\\b", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private String normalizeUnit(String unit) {
        if (unit == null) return "pcs";
        String u = unit.toLowerCase(Locale.ROOT);
        return switch (u) {
            case "kgs" -> "kg";
            case "gm", "gms", "grams" -> "g";
            case "ltr", "litre", "litres" -> "l";
            case "packets", "pkt", "pkts", "pack", "packs" -> "packet";
            case "bottles", "btl" -> "bottle";
            case "boxes" -> "box";
            case "bags" -> "bag";
            case "pc", "piece", "pieces" -> "pcs";
            default -> u;
        };
    }

    private boolean isWeightOrVolumeUnit(String unit) {
        return "kg".equals(unit) || "g".equals(unit) || "l".equals(unit) || "ml".equals(unit);
    }

    private int safeInt(String s, int fallback) {
        try {
            return Math.max(1, Integer.parseInt(s));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private long elapsed(long start) {
        return Math.max(1L, System.currentTimeMillis() - start);
    }

    private boolean matchesAny(String text, String regex) {
        return text.matches(regex);
    }

    private IntentType parseIntent(String raw) {
        try {
            return IntentType.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (Exception e) {
            return IntentType.UNKNOWN;
        }
    }

    private String cleanJsonFence(String text) {
        String trimmed = text == null ? "{}" : text.trim();
        if (trimmed.startsWith("```")) {
            trimmed = trimmed.replaceAll("^```(?:json)?\\s*", "").replaceAll("\\s*```$", "");
        }
        return trimmed.trim();
    }

    private String buildSystemPrompt(ConversationState state, String cartSummary, List<String> catalogHints) {
        String catalogBlock = (catalogHints == null || catalogHints.isEmpty())
                ? "Not provided"
                : String.join(", ", catalogHints.stream().limit(35).toList());
        String cartBlock = (cartSummary == null || cartSummary.isBlank()) ? "Empty" : cartSummary;

        return """
                You are an AI NLP extractor for DRC Krishna Gen Store's WhatsApp grocery bot in India.
                Customers write in English, Hindi, or Hinglish (e.g. "Bhaiya 2 packet Amul milk, 1 brown bread, aur 5kg Aashirvaad atta deliver kar do").
                Current conversation state: %s.
                Current cart: %s.
                Available store SKUs: %s.
                Extract ONLY valid JSON matching this schema:
                {
                  "intent": "ORDER|ADD_ITEMS|UPDATE_QUANTITY|REMOVE_ITEMS|VIEW_CART|CLEAR_CART|SET_ADDRESS|CONFIRM_ORDER|PAY_UPI|PAY_COD|CHECK_STATUS|ORDER_HISTORY|REORDER|CANCEL_ORDER|BROWSE_CATALOG|GREETING|HELP|UNKNOWN",
                  "items": [
                    {
                      "rawText": "original phrase",
                      "productQuery": "normalized product name without count/fillers",
                      "quantity": 1,
                      "unit": "packet|kg|g|l|ml|bottle|box|bag|pcs",
                      "packSizeMention": "5kg or 500g or null if not mentioned"
                    }
                  ],
                  "deliveryAddress": "extracted street/flat address or null",
                  "normalizedSummary": "short 1-line summary of what the user wants"
                }
                Important rules:
                - For "5kg Aashirvaad atta", quantity is 1 (one pack) and packSizeMention is "5kg".
                - For "2 x 5kg atta", quantity is 2 and packSizeMention is "5kg".
                - For "2 packet Amul milk", quantity is 2, unit is "packet", packSizeMention is null.
                - Use UPDATE_QUANTITY when user asks to set/change an existing item's quantity (e.g. "make milk 3").
                - Translate Hindi numbers ("ek"=1, "do"=2, "teen"=3, "char"=4, "paanch"=5).
                """.formatted(state.name(), cartBlock, catalogBlock);
    }
}


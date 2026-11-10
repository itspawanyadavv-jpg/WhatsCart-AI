package com.drc.whatsappbot.service;

import com.drc.whatsappbot.config.BotProperties;
import com.drc.whatsappbot.dto.*;
import com.drc.whatsappbot.enums.*;
import com.drc.whatsappbot.model.BotOrder;
import com.drc.whatsappbot.model.BotOrderItem;
import com.drc.whatsappbot.model.CatalogProduct;
import com.drc.whatsappbot.model.ChatMessage;
import com.drc.whatsappbot.model.ChatSession;
import com.drc.whatsappbot.repository.BotOrderRepository;
import com.drc.whatsappbot.repository.CatalogProductRepository;
import com.drc.whatsappbot.repository.ChatMessageRepository;
import com.drc.whatsappbot.repository.ChatSessionRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class ConversationEngineService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Pattern ORDER_CODE_PATTERN = Pattern.compile("\\b(DRC-WA-\\d{4,6})\\b", Pattern.CASE_INSENSITIVE);

    private final ChatSessionRepository sessionRepo;
    private final ChatMessageRepository messageRepo;
    private final BotOrderRepository orderRepo;
    private final CatalogProductRepository catalogRepo;
    private final DrcCatalogSyncService catalogService;
    private final LlmHinglishParserService parserService;
    private final FuzzyCatalogMatcherService matcherService;
    private final RazorpayPaymentLinkService paymentLinkService;
    private final WhatsAppGatewayService gatewayService;
    private final SseEventBusService sseBus;
    private final BotProperties props;
    private final ObjectMapper mapper;

    public ConversationEngineService(ChatSessionRepository sessionRepo,
                                     ChatMessageRepository messageRepo,
                                     BotOrderRepository orderRepo,
                                     CatalogProductRepository catalogRepo,
                                     DrcCatalogSyncService catalogService,
                                     LlmHinglishParserService parserService,
                                     FuzzyCatalogMatcherService matcherService,
                                     RazorpayPaymentLinkService paymentLinkService,
                                     WhatsAppGatewayService gatewayService,
                                     SseEventBusService sseBus,
                                     BotProperties props,
                                     ObjectMapper mapper) {
        this.sessionRepo = sessionRepo;
        this.messageRepo = messageRepo;
        this.orderRepo = orderRepo;
        this.catalogRepo = catalogRepo;
        this.catalogService = catalogService;
        this.parserService = parserService;
        this.matcherService = matcherService;
        this.paymentLinkService = paymentLinkService;
        this.gatewayService = gatewayService;
        this.sseBus = sseBus;
        this.props = props;
        this.mapper = mapper;
    }

    @Transactional
    public ChatTurnResponse handleInboundMessage(String rawPhone, String customerName, String messageText, MessageChannel channel) {
        String phone = normalizePhone(rawPhone);
        ChatSession session = loadOrCreateSession(phone, customerName);

        gatewayService.recordInbound(phone, channel, messageText, Map.of("state", session.getState().name()));

        List<CartItemEntry> cart = readCart(session);
        List<CatalogProduct> catalog = catalogService.getAllProducts();
        String cartSummary = summarizeCart(cart);
        List<String> catalogHints = catalog.stream().map(CatalogProduct::getName).toList();
        LlmParseResult parseResult = parserService.parse(messageText, session.getState(), cartSummary, catalogHints);
        return processParsedTurn(session, channel, messageText, parseResult, catalog, cart);
    }

    @Transactional
    public ChatTurnResponse handleInboundMedia(String rawPhone,
                                               String customerName,
                                               byte[] mediaBytes,
                                               String mimeType,
                                               String caption,
                                               MessageChannel channel) {
        String phone = normalizePhone(rawPhone);
        ChatSession session = loadOrCreateSession(phone, customerName);
        String label = (mimeType != null && mimeType.startsWith("audio")) ? "[Voice Note]" : "[Photo / Attachment]";
        String inboundBody = (caption != null && !caption.isBlank()) ? label + " " + caption.trim() : label;

        gatewayService.recordInbound(phone, channel, inboundBody, Map.of(
                "state", session.getState().name(),
                "mediaType", mimeType == null ? "unknown" : mimeType
        ));

        List<CartItemEntry> cart = readCart(session);
        List<CatalogProduct> catalog = catalogService.getAllProducts();
        List<String> catalogHints = catalog.stream().map(CatalogProduct::getName).toList();

        Optional<LlmParseResult> multimodal = parserService.parseMediaWithGemini(
                mediaBytes, mimeType, caption, session.getState(), summarizeCart(cart), catalogHints);
        if (multimodal.isPresent() && !multimodal.get().items().isEmpty()) {
            return processParsedTurn(session, channel, inboundBody, multimodal.get(), catalog, cart);
        }

        if (caption != null && !caption.isBlank()) {
            LlmParseResult captionParsed = parserService.parse(caption, session.getState(), summarizeCart(cart), catalogHints);
            if (captionParsed.intent() != IntentType.UNKNOWN) {
                return processParsedTurn(session, channel, caption, captionParsed, catalog, cart);
            }
        }

        StoreRulesDto rules = catalogService.getStoreRules();
        String replyText = (mimeType != null && mimeType.startsWith("audio"))
                ? "🎤 Voice note received! Please type your grocery list in Hinglish text (e.g., _\"2 packet Amul milk aur 5kg Aashirvaad atta\"_) so I can add it to your cart."
                : "📷 Image received! Please send your grocery list as text or attach a caption (e.g., _\"2 packet Amul milk aur 1 brown bread\"_).";
        Map<String, Object> replyMeta = Map.of(
                "intent", "MEDIA_PROMPT",
                "quickReplies", List.of("2 packet Amul milk, 1 brown bread, aur 5kg Aashirvaad atta", "Catalog dikhao")
        );
        session.setUpdatedAt(LocalDateTime.now());
        sessionRepo.save(session);
        boolean dispatchExternal = channel != MessageChannel.TWILIO_WHATSAPP;
        gatewayService.sendReply(phone, channel, replyText, replyMeta, dispatchExternal);
        return buildTurnResponse(session, cart, rules, null, List.of());
    }

    private ChatSession loadOrCreateSession(String phone, String customerName) {
        ChatSession session = sessionRepo.findByPhone(phone).orElseGet(() -> createSession(phone, customerName));
        if (customerName != null && !customerName.isBlank()) {
            session.setCustomerName(customerName.trim());
        }
        return session;
    }

    private String summarizeCart(List<CartItemEntry> cart) {
        if (cart.isEmpty()) return "";
        return cart.stream()
                .map(i -> i.quantity() + "x " + i.productName())
                .reduce((a, b) -> a + ", " + b)
                .orElse("");
    }

    private ChatTurnResponse processParsedTurn(ChatSession session,
                                               MessageChannel channel,
                                               String messageText,
                                               LlmParseResult parseResult,
                                               List<CatalogProduct> catalog,
                                               List<CartItemEntry> cart) {
        String phone = session.getPhone();
        List<CatalogMatchResult> matchResults = new ArrayList<>();
        StoreRulesDto rules = catalogService.getStoreRules();

        String replyText;
        Map<String, Object> replyMeta = new LinkedHashMap<>();
        replyMeta.put("intent", parseResult.intent().name());
        replyMeta.put("parserUsed", parseResult.parserUsed());

        switch (parseResult.intent()) {
            case GREETING -> {
                replyText = buildGreetingReply(session);
                replyMeta.put("quickReplies", List.of(
                        "2 packet Amul milk, 1 brown bread, aur 5kg Aashirvaad atta",
                        "Catalog dikhao",
                        "Order status"
                ));
            }
            case HELP -> {
                replyText = buildHelpReply(rules);
                replyMeta.put("quickReplies", List.of(
                        "2 Fortune oil aur 1 Tata salt bhejo",
                        "Catalog dikhao",
                        "View cart"
                ));
            }
            case BROWSE_CATALOG -> {
                replyText = buildCatalogSummaryReply();
                replyMeta.put("quickReplies", List.of(
                        "2 packet Amul milk, 1 brown bread, aur 5kg Aashirvaad atta",
                        "1 kg Toor dal aur 1 Tata tea bhejo"
                ));
            }
            case VIEW_CART -> {
                if (cart.isEmpty()) {
                    replyText = "🛒 Your cart is empty right now.\nJust text me what you need in Hindi or English — e.g., _\"2 packet Amul milk aur 5kg Aashirvaad atta bhejo\"_";
                } else {
                    replyText = buildBillSummaryReply(cart, session, rules, List.of());
                    replyMeta.put("quickReplies", buildCartQuickReplies(session));
                }
            }
            case CLEAR_CART -> {
                cart.clear();
                session.setState(ConversationState.IDLE);
                saveCart(session, cart);
                replyText = "🗑️ Cart cleared! Send me a fresh grocery list anytime.";
                replyMeta.put("quickReplies", List.of(
                        "2 packet Amul milk, 1 brown bread, aur 5kg Aashirvaad atta",
                        "Catalog dikhao"
                ));
            }
            case REMOVE_ITEMS -> {
                replyText = handleRemoveItems(parseResult, cart, session, rules);
                replyMeta.put("quickReplies", buildCartQuickReplies(session));
            }
            case UPDATE_QUANTITY -> {
                for (ParsedItem parsedItem : parseResult.items()) {
                    FuzzyCatalogMatcherService.ResolvedMatch resolved = matcherService.match(parsedItem, catalog);
                    matchResults.add(resolved.result());
                    if (resolved.result().matched() && !resolved.result().outOfStock() && resolved.effectiveQty() > 0) {
                        setCartItemQuantity(cart, resolved.result(), resolved.effectiveQty());
                    }
                }
                saveCart(session, cart);
                if (!cart.isEmpty()) {
                    session.setState(ConversationState.REVIEWING_CART);
                }
                replyText = buildBillSummaryReply(cart, session, rules, matchResults);
                replyMeta.put("quickReplies", buildCartQuickReplies(session));
            }
            case ORDER, ADD_ITEMS -> {
                if (parseResult.deliveryAddress() != null && !parseResult.deliveryAddress().isBlank()) {
                    session.setDeliveryAddress(parseResult.deliveryAddress().trim());
                }
                for (ParsedItem parsedItem : parseResult.items()) {
                    FuzzyCatalogMatcherService.ResolvedMatch resolved = matcherService.match(parsedItem, catalog);
                    matchResults.add(resolved.result());
                    if (resolved.result().matched() && !resolved.result().outOfStock() && resolved.effectiveQty() > 0) {
                        mergeIntoCart(cart, resolved.result(), resolved.effectiveQty());
                    }
                }
                saveCart(session, cart);
                if (!cart.isEmpty()) {
                    session.setState(ConversationState.REVIEWING_CART);
                }
                replyText = buildBillSummaryReply(cart, session, rules, matchResults);
                replyMeta.put("quickReplies", buildCartQuickReplies(session));
            }
            case SET_ADDRESS -> {
                String addr = parseResult.deliveryAddress() != null && !parseResult.deliveryAddress().isBlank()
                        ? parseResult.deliveryAddress().trim()
                        : messageText.trim();
                session.setDeliveryAddress(addr);
                if (cart.isEmpty()) {
                    session.setState(ConversationState.IDLE);
                    replyText = "📍 Delivery address saved: *" + addr + "*\n\nNow send me your grocery list to order!";
                } else {
                    session.setState(ConversationState.AWAITING_PAYMENT);
                    BillTotals totals = computeTotals(cart, rules);
                    replyText = "📍 Address saved: *" + addr + "*\n\n"
                            + "🧾 Total Payable: *₹" + formatMoney(totals.total()) + "*\n\n"
                            + "How would you like to pay?\n"
                            + "• Reply *UPI* for instant Razorpay UPI payment link\n"
                            + "• Reply *COD* for Cash on Delivery";
                    replyMeta.put("quickReplies", List.of("Pay via UPI", "Cash on Delivery (COD)"));
                }
            }
            case CONFIRM_ORDER -> {
                if (cart.isEmpty()) {
                    replyText = "🛒 Your cart is empty! Text me items like _\"2 Amul milk aur 1 Tata salt\"_ first.";
                } else {
                    BillTotals totals = computeTotals(cart, rules);
                    if (totals.subtotal().compareTo(rules.minOrderValue()) < 0) {
                        BigDecimal needed = rules.minOrderValue().subtract(totals.subtotal());
                        replyText = "⚠️ Minimum order value is *₹" + formatMoney(rules.minOrderValue()) + "*.\n"
                                + "Your current subtotal is ₹" + formatMoney(totals.subtotal())
                                + ". Please add items worth *₹" + formatMoney(needed) + "* more!";
                        replyMeta.put("quickReplies", List.of("Add 1 Parle-G", "Add 1 Tata Salt"));
                    } else if (session.getDeliveryAddress() == null || session.getDeliveryAddress().isBlank()) {
                        session.setState(ConversationState.AWAITING_ADDRESS);
                        replyText = "🏠 Great! Before checkout, please share your *delivery address* (e.g., _Flat 402, Krishna Heights, Sector 12_).";
                    } else {
                        session.setState(ConversationState.AWAITING_PAYMENT);
                        replyText = "✅ Order ready for *" + session.getDeliveryAddress() + "*\n"
                                + "💰 Total: *₹" + formatMoney(totals.total()) + "*\n\n"
                                + "Choose payment mode:\n"
                                + "• Reply *UPI* to get your Razorpay UPI link\n"
                                + "• Reply *COD* for Cash on Delivery";
                        replyMeta.put("quickReplies", List.of("Pay via UPI", "Cash on Delivery (COD)"));
                    }
                }
            }
            case PAY_UPI -> {
                String validationError = validateCheckoutReady(cart, session, rules, "UPI");
                if (validationError != null) {
                    replyText = validationError;
                } else {
                    BillTotals totals = computeTotals(cart, rules);
                    BotOrder order = placeOrder(session, cart, totals, "UPI_LINK", channel);
                    RazorpayPaymentLinkService.PaymentLinkOutcome link = paymentLinkService.createPaymentLink(order);
                    order.setRazorpayPaymentLinkId(link.linkId());
                    order.setRazorpayPaymentLinkUrl(link.shortUrl());
                    order.setUpiDeepLink(link.upiDeepLink());
                    orderRepo.save(order);

                    cart.clear();
                    saveCart(session, cart);
                    session.setState(ConversationState.IDLE);
                    session.setActiveOrderCode(order.getOrderCode());
                    sseBus.publish("merchant", "order_update", order);

                    replyText = "💳 *UPI Payment Link Ready!*\n"
                            + "Order ID: *" + order.getOrderCode() + "*\n"
                            + "Amount: *₹" + formatMoney(order.getTotal()) + "*\n"
                            + "Deliver to: " + order.getDeliveryAddress() + "\n\n"
                            + "🔗 Pay securely via Razorpay UPI:\n" + link.shortUrl() + "\n\n"
                            + "_As soon as your payment succeeds, you'll receive an instant confirmation & delivery OTP here._";

                    replyMeta.put("paymentCard", Map.of(
                            "orderCode", order.getOrderCode(),
                            "amount", order.getTotal(),
                            "shortUrl", link.shortUrl(),
                            "upiDeepLink", link.upiDeepLink(),
                            "liveRazorpay", link.liveRazorpay()
                    ));
                    replyMeta.put("quickReplies", List.of("Order status"));
                }
            }
            case PAY_COD -> {
                String validationError = validateCheckoutReady(cart, session, rules, "Cash on Delivery");
                if (validationError != null) {
                    replyText = validationError;
                } else {
                    BillTotals totals = computeTotals(cart, rules);
                    BotOrder order = placeOrder(session, cart, totals, "COD", channel);
                    order.setPaymentStatus(BotPaymentStatus.COD);
                    order.setStatus(BotOrderStatus.CONFIRMED);
                    orderRepo.save(order);

                    cart.clear();
                    saveCart(session, cart);
                    session.setState(ConversationState.IDLE);
                    session.setActiveOrderCode(order.getOrderCode());
                    sseBus.publish("merchant", "order_update", order);

                    replyText = "✅ *Order Confirmed (Cash on Delivery)!*\n"
                            + "Order ID: *" + order.getOrderCode() + "*\n"
                            + "Payable on delivery: *₹" + formatMoney(order.getTotal()) + "*\n"
                            + "Address: " + order.getDeliveryAddress() + "\n"
                            + "🔐 Delivery OTP: *" + order.getDeliveryOtp() + "*\n\n"
                            + "We'll notify you automatically as your order is packed and out for delivery! 🛵";
                    replyMeta.put("quickReplies", List.of("Order status"));
                }
            }
            case CANCEL_ORDER -> {
                replyText = handleCancelOrder(cart, session);
                replyMeta.put("quickReplies", List.of("Catalog dikhao", "2 packet Amul milk aur 1 Tata salt"));
            }
            case ORDER_HISTORY -> {
                replyText = buildOrderHistoryReply(session);
                replyMeta.put("quickReplies", List.of("Repeat last order", "Order status", "Catalog dikhao"));
            }
            case REORDER -> {
                replyText = handleReorder(cart, session, catalog, rules);
                replyMeta.put("quickReplies", cart.isEmpty()
                        ? List.of("Catalog dikhao", "2 packet Amul milk aur 1 Tata salt")
                        : buildCartQuickReplies(session));
            }
            case CHECK_STATUS -> {
                replyText = buildOrderStatusReply(session, messageText);
                replyMeta.put("quickReplies", List.of("Repeat last order", "My orders", "Catalog dikhao"));
            }
            default -> {
                replyText = "🤔 Samajh nahi aaya — try texting your grocery list in simple Hinglish, like:\n"
                        + "• _\"Bhaiya 2 packet Amul milk, 1 brown bread, aur 5kg Aashirvaad atta deliver kar do\"_\n"
                        + "• _\"Catalog dikhao\"_ or _\"Order status\"_";
                replyMeta.put("quickReplies", List.of(
                        "2 packet Amul milk, 1 brown bread, aur 5kg Aashirvaad atta",
                        "Catalog dikhao",
                        "Help"
                ));
            }
        }

        session.setUpdatedAt(LocalDateTime.now());
        sessionRepo.save(session);

        // Twilio returns its synchronous reply as TwiML in TwilioWebhookController; external REST dispatch would double-send
        boolean dispatchExternal = channel != MessageChannel.TWILIO_WHATSAPP;
        gatewayService.sendReply(phone, channel, replyText, replyMeta, dispatchExternal);

        return buildTurnResponse(session, cart, rules, parseResult, matchResults);
    }

    @Transactional
    public BotOrder markPaymentSucceeded(String orderCode, String razorpayPaymentId) {
        BotOrder order = orderRepo.findByOrderCode(orderCode)
                .orElseThrow(() -> new IllegalArgumentException("Order not found: " + orderCode));
        if (order.getStatus() == BotOrderStatus.CANCELLED) {
            throw new IllegalStateException("Cannot confirm payment for cancelled order: " + orderCode);
        }
        if (order.getPaymentStatus() == BotPaymentStatus.PAID) {
            return order;
        }
        order.setPaymentStatus(BotPaymentStatus.PAID);
        order.setRazorpayPaymentId(razorpayPaymentId != null ? razorpayPaymentId : "pay_demo_" + System.currentTimeMillis());
        if (order.getStatus() == BotOrderStatus.PLACED) {
            order.setStatus(BotOrderStatus.CONFIRMED);
        }
        order.setUpdatedAt(LocalDateTime.now());
        BotOrder saved = orderRepo.save(order);

        sseBus.publish("merchant", "order_update", saved);
        sseBus.publish("chat:" + saved.getPhone(), "order_update", saved);

        String alertMsg = "✅ *Payment Received via UPI!*\n"
                + "Order *" + saved.getOrderCode() + "* (₹" + formatMoney(saved.getTotal()) + ") is now *CONFIRMED*.\n"
                + "🔐 Your Delivery OTP: *" + saved.getDeliveryOtp() + "*\n\n"
                + "Our store team is preparing your groceries now! 🛍️";

        gatewayService.sendReply(saved.getPhone(), saved.getChannel(), alertMsg, Map.of(
                "type", "PAYMENT_ALERT",
                "orderCode", saved.getOrderCode(),
                "status", saved.getStatus().name()
        ));

        return saved;
    }

    @Transactional
    public void handlePaymentLinkFailureOrExpiry(String orderCode, String eventType) {
        BotOrder order = orderRepo.findByOrderCode(orderCode)
                .orElseThrow(() -> new IllegalArgumentException("Order not found: " + orderCode));
        if (order.getPaymentStatus() == BotPaymentStatus.PAID
                || order.getStatus() == BotOrderStatus.CANCELLED
                || order.getStatus() == BotOrderStatus.DELIVERED) {
            return;
        }

        String reason = "payment.failed".equals(eventType)
                ? "Your UPI payment attempt failed"
                : "Your UPI payment link expired or was cancelled";
        String alertMsg = "⚠️ *" + reason + "* for order *" + order.getOrderCode() + "* (₹" + formatMoney(order.getTotal()) + ").\n"
                + "You can still use the payment link, or contact the store / reorder anytime.";

        gatewayService.sendReply(order.getPhone(), order.getChannel(), alertMsg, Map.of(
                "type", "PAYMENT_ALERT",
                "orderCode", order.getOrderCode(),
                "event", eventType
        ));
    }

    @Transactional
    public BotOrder updateOrderStatus(String orderCode, BotOrderStatus newStatus, String riderName) {
        return updateOrderStatus(orderCode, newStatus, riderName, null);
    }

    @Transactional
    public BotOrder updateOrderStatus(String orderCode, BotOrderStatus newStatus, String riderName, String otp) {
        BotOrder order = orderRepo.findByOrderCode(orderCode)
                .orElseThrow(() -> new IllegalArgumentException("Order not found: " + orderCode));
        if (order.getStatus() == BotOrderStatus.DELIVERED || order.getStatus() == BotOrderStatus.CANCELLED) {
            throw new IllegalStateException("Order " + orderCode + " is already " + order.getStatus() + " and cannot be updated");
        }
        if (newStatus == BotOrderStatus.DELIVERED && otp != null && !otp.isBlank()) {
            if (!otp.trim().equals(order.getDeliveryOtp())) {
                throw new IllegalStateException("Invalid delivery OTP for order " + orderCode);
            }
        }

        order.setStatus(newStatus);
        order.setUpdatedAt(LocalDateTime.now());
        if (newStatus == BotOrderStatus.DELIVERED && order.getPaymentStatus() == BotPaymentStatus.COD) {
            order.setPaymentStatus(BotPaymentStatus.PAID);
        } else if (newStatus == BotOrderStatus.CANCELLED) {
            restoreOrderStock(order);
        }
        BotOrder saved = orderRepo.save(order);

        sseBus.publish("merchant", "order_update", saved);
        sseBus.publish("chat:" + saved.getPhone(), "order_update", saved);

        String rider = (riderName != null && !riderName.isBlank()) ? riderName.trim() : "Ramesh";
        String alertMsg = switch (newStatus) {
            case CONFIRMED -> "✅ *Order Confirmed!* Your order *" + saved.getOrderCode() + "* has been accepted by " + props.getStoreName() + ".";
            case PACKING -> "📦 *Packing in Progress!* We are carefully packing your " + saved.getItems().size() + " items for order *" + saved.getOrderCode() + "*.";
            case OUT_FOR_DELIVERY -> "🛵 *Out for Delivery!* Rider *" + rider + "* has left the store with order *" + saved.getOrderCode() + "*.\n"
                    + "📍 Address: " + saved.getDeliveryAddress() + "\n"
                    + "🔐 Share Delivery OTP *" + saved.getDeliveryOtp() + "* at your doorstep.";
            case DELIVERED -> "🎉 *Delivered!* Order *" + saved.getOrderCode() + "* has been delivered. Dhanyawad for shopping with *" + props.getStoreName() + "*! 🙏";
            case CANCELLED -> "❌ *Order Cancelled:* Your order *" + saved.getOrderCode() + "* was cancelled. Text us anytime to reorder.";
            case PLACED -> "📝 Order *" + saved.getOrderCode() + "* is placed and awaiting store confirmation.";
        };

        gatewayService.sendReply(saved.getPhone(), saved.getChannel(), alertMsg, Map.of(
                "type", "STATUS_ALERT",
                "orderCode", saved.getOrderCode(),
                "status", newStatus.name()
        ));

        return saved;
    }

    @Transactional(readOnly = true)
    public ChatTurnResponse getSessionState(String rawPhone) {
        String phone = normalizePhone(rawPhone);
        ChatSession session = sessionRepo.findByPhone(phone).orElseGet(() -> {
            ChatSession s = new ChatSession();
            s.setPhone(phone);
            s.setCustomerName("Walk-in Customer");
            s.setState(ConversationState.IDLE);
            s.setCartJson("[]");
            return s;
        });
        List<CartItemEntry> cart = readCart(session);
        return buildTurnResponse(session, cart, catalogService.getStoreRules(), null, List.of());
    }

    @Transactional
    public ChatTurnResponse resetSession(String rawPhone, String customerName) {
        String phone = normalizePhone(rawPhone);
        messageRepo.deleteByPhone(phone);
        sessionRepo.findByPhone(phone).ifPresent(sessionRepo::delete);
        ChatSession fresh = createSession(phone, customerName);
        gatewayService.sendReply(phone, MessageChannel.SIMULATOR, buildGreetingReply(fresh), Map.of(
                "quickReplies", List.of(
                        "Bhaiya 2 packet Amul milk, 1 brown bread, aur 5kg Aashirvaad atta deliver kar do",
                        "2 Fortune oil, 1 kg Toor dal, aur 1 Tata chai bhejo",
                        "Catalog dikhao"
                )
        ));
        return buildTurnResponse(fresh, List.of(), catalogService.getStoreRules(), null, List.of());
    }

    private ChatTurnResponse buildTurnResponse(ChatSession session,
                                             List<CartItemEntry> cart,
                                             StoreRulesDto rules,
                                             LlmParseResult parseResult,
                                             List<CatalogMatchResult> matchResults) {
        BillTotals totals = computeTotals(cart, rules);
        List<ChatMessage> messages = messageRepo.findByPhoneOrderByCreatedAtAscIdAsc(session.getPhone());
        BotOrder activeOrder = null;
        if (session.getActiveOrderCode() != null) {
            activeOrder = orderRepo.findByOrderCode(session.getActiveOrderCode()).orElse(null);
        }
        return new ChatTurnResponse(
                session.getPhone(),
                session.getCustomerName(),
                session.getState(),
                session.getDeliveryAddress(),
                cart,
                totals.subtotal(),
                totals.deliveryFee(),
                totals.total(),
                totals.amountForFreeDelivery(),
                rules,
                parseResult,
                matchResults,
                messages,
                activeOrder
        );
    }

    private String validateCheckoutReady(List<CartItemEntry> cart, ChatSession session, StoreRulesDto rules, String paymentName) {
        if (cart.isEmpty()) {
            return "🛒 Your cart is empty. Send your grocery list first!";
        }
        if (session.getDeliveryAddress() == null || session.getDeliveryAddress().isBlank()) {
            session.setState(ConversationState.AWAITING_ADDRESS);
            return "🏠 Please share your delivery address first so I can proceed with " + paymentName + ".";
        }
        BillTotals totals = computeTotals(cart, rules);
        if (totals.subtotal().compareTo(rules.minOrderValue()) < 0) {
            return "⚠️ Minimum order value is ₹" + formatMoney(rules.minOrderValue()) + ". Please add a few more items.";
        }
        for (CartItemEntry entry : cart) {
            if (entry.productId() != null) {
                Optional<CatalogProduct> maybeProd = catalogRepo.findById(entry.productId());
                if (maybeProd.isPresent() && maybeProd.get().getStock() < entry.quantity()) {
                    return "⚠️ Only " + maybeProd.get().getStock() + " left in stock for *"
                            + entry.productName() + "*. Please update or remove it from your cart.";
                }
            }
        }
        return null;
    }

    private BotOrder placeOrder(ChatSession session, List<CartItemEntry> cart, BillTotals totals, String paymentMethod, MessageChannel channel) {
        BotOrder order = new BotOrder();
        order.setOrderCode(generateOrderCode());
        order.setPhone(session.getPhone());
        order.setCustomerName(session.getCustomerName() == null ? "WhatsApp Customer" : session.getCustomerName());
        order.setDeliveryAddress(session.getDeliveryAddress());
        order.setStatus(BotOrderStatus.PLACED);
        order.setPaymentMethod(paymentMethod);
        order.setPaymentStatus(BotPaymentStatus.PENDING);
        order.setSubtotal(totals.subtotal());
        order.setDeliveryFee(totals.deliveryFee());
        order.setTotal(totals.total());
        order.setChannel(channel);
        order.setDeliveryOtp(String.valueOf(1000 + RANDOM.nextInt(9000)));
        order.setCreatedAt(LocalDateTime.now());
        order.setUpdatedAt(LocalDateTime.now());

        for (CartItemEntry entry : cart) {
            BotOrderItem item = new BotOrderItem();
            item.setOrder(order);
            item.setProductId(entry.productId());
            item.setProductName(entry.productName());
            item.setUnit(entry.unit());
            item.setUnitPrice(entry.unitPrice());
            item.setQuantity(entry.quantity());
            item.setLineTotal(entry.lineTotal());
            item.setRawQuery(entry.rawQuery());
            item.setMatchConfidence(entry.confidence());
            order.getItems().add(item);

            if (entry.productId() != null) {
                catalogRepo.findById(entry.productId()).ifPresent(prod -> {
                    prod.setStock(Math.max(0, prod.getStock() - entry.quantity()));
                    catalogRepo.save(prod);
                });
            }
        }
        return orderRepo.save(order);
    }

    private void restoreOrderStock(BotOrder order) {
        for (BotOrderItem item : order.getItems()) {
            if (item.getProductId() != null) {
                catalogRepo.findById(item.getProductId()).ifPresent(prod -> {
                    prod.setStock(prod.getStock() + item.getQuantity());
                    catalogRepo.save(prod);
                });
            }
        }
    }

    private String handleCancelOrder(List<CartItemEntry> cart, ChatSession session) {
        Optional<BotOrder> active = orderRepo.findByPhoneOrderByCreatedAtDesc(session.getPhone()).stream()
                .filter(o -> o.getStatus() != BotOrderStatus.DELIVERED && o.getStatus() != BotOrderStatus.CANCELLED)
                .findFirst();

        if (active.isPresent()) {
            BotOrder order = active.get();
            order.setStatus(BotOrderStatus.CANCELLED);
            order.setUpdatedAt(LocalDateTime.now());
            restoreOrderStock(order);
            BotOrder saved = orderRepo.save(order);

            if (saved.getOrderCode().equals(session.getActiveOrderCode())) {
                session.setActiveOrderCode(null);
            }
            sseBus.publish("merchant", "order_update", saved);
            sseBus.publish("chat:" + saved.getPhone(), "order_update", saved);

            String refundNote = saved.getPaymentStatus() == BotPaymentStatus.PAID
                    ? "\n💳 Any paid UPI amount will be refunded automatically within 3–5 business days."
                    : "";
            return "❌ *Order Cancelled:* *" + saved.getOrderCode() + "* has been cancelled and store stock restored."
                    + refundNote + "\nSend a fresh grocery list anytime!";
        }

        if (!cart.isEmpty()) {
            cart.clear();
            saveCart(session, cart);
            session.setState(ConversationState.IDLE);
            return "🗑️ No placed order to cancel — cleared your current cart instead!";
        }

        return "📭 You don't have an active order to cancel right now.";
    }

    private String handleRemoveItems(LlmParseResult parseResult, List<CartItemEntry> cart, ChatSession session, StoreRulesDto rules) {
        if (cart.isEmpty()) {
            return "🛒 Your cart is already empty.";
        }
        if (parseResult.items().isEmpty()) {
            return "Tell me which item to remove, e.g., _\"remove brown bread\"_ or _\"clear cart\"_.";
        }
        int modifiedCount = 0;
        for (ParsedItem target : parseResult.items()) {
            String q = target.productQuery().toLowerCase(Locale.ROOT);
            boolean explicitQty = target.rawText() != null && target.rawText().trim().matches("^(?:\\d+\\b.*|.*\\b\\d+)$");
            for (int i = 0; i < cart.size(); i++) {
                CartItemEntry entry = cart.get(i);
                if (entry.productName().toLowerCase(Locale.ROOT).contains(q)
                        || (entry.rawQuery() != null && entry.rawQuery().toLowerCase(Locale.ROOT).contains(q))) {
                    if (explicitQty && entry.quantity() > target.quantity()) {
                        cart.set(i, new CartItemEntry(
                                entry.productId(),
                                entry.productName(),
                                entry.unit(),
                                entry.unitPrice(),
                                entry.quantity() - target.quantity(),
                                entry.rawQuery(),
                                entry.confidence()
                        ));
                    } else {
                        cart.remove(i);
                    }
                    modifiedCount++;
                    break;
                }
            }
        }
        saveCart(session, cart);
        if (modifiedCount == 0) {
            return "Could not find that item in your cart.\n\n" + buildBillSummaryReply(cart, session, rules, List.of());
        }
        if (cart.isEmpty()) {
            session.setState(ConversationState.IDLE);
            return "🗑️ Removed! Your cart is now empty.";
        }
        return "✂️ Cart updated! Updated bill:\n\n" + buildBillSummaryReply(cart, session, rules, List.of());
    }

    private void mergeIntoCart(List<CartItemEntry> cart, CatalogMatchResult matched, int qtyToAdd) {
        for (int i = 0; i < cart.size(); i++) {
            CartItemEntry existing = cart.get(i);
            if (Objects.equals(existing.productId(), matched.productId())) {
                int newQty = Math.min(matched.availableStock(), existing.quantity() + qtyToAdd);
                cart.set(i, new CartItemEntry(
                        existing.productId(),
                        existing.productName(),
                        existing.unit(),
                        existing.unitPrice(),
                        newQty,
                        matched.rawQuery(),
                        matched.confidence()
                ));
                return;
            }
        }
        cart.add(new CartItemEntry(
                matched.productId(),
                matched.matchedProductName(),
                matched.unit(),
                matched.unitPrice(),
                qtyToAdd,
                matched.rawQuery(),
                matched.confidence()
        ));
    }

    private void setCartItemQuantity(List<CartItemEntry> cart, CatalogMatchResult matched, int exactQty) {
        int boundedQty = Math.min(matched.availableStock(), Math.max(1, exactQty));
        for (int i = 0; i < cart.size(); i++) {
            CartItemEntry existing = cart.get(i);
            if (Objects.equals(existing.productId(), matched.productId())) {
                cart.set(i, new CartItemEntry(
                        existing.productId(),
                        existing.productName(),
                        existing.unit(),
                        existing.unitPrice(),
                        boundedQty,
                        matched.rawQuery(),
                        matched.confidence()
                ));
                return;
            }
        }
        cart.add(new CartItemEntry(
                matched.productId(),
                matched.matchedProductName(),
                matched.unit(),
                matched.unitPrice(),
                boundedQty,
                matched.rawQuery(),
                matched.confidence()
        ));
    }

    private String buildGreetingReply(ChatSession session) {
        String name = session.getCustomerName() != null && !session.getCustomerName().isBlank()
                ? session.getCustomerName()
                : "Ji";
        return "🙏 Namaste *" + name + "*! Welcome to *" + props.getStoreName() + "* on WhatsApp.\n\n"
                + "Just send your grocery list in Hindi, English, or Hinglish — for example:\n"
                + "• _\"Bhaiya 2 packet Amul milk, 1 brown bread, aur 5kg Aashirvaad atta deliver kar do\"_\n"
                + "• _\"2 Fortune oil aur 1 Tata salt bhejo\"_\n\n"
                + "I'll match live store stock, build your bill, and share an instant Razorpay UPI link or COD checkout! 🛒";
    }

    private String buildHelpReply(StoreRulesDto rules) {
        return "📖 *How to Order on WhatsApp:*\n"
                + "1️⃣ Type items naturally: _\"2 packet Amul milk, 1 brown bread, aur 5kg Aashirvaad atta\"_\n"
                + "2️⃣ Edit anytime: _\"add 1 Parle-G\"_ or _\"remove bread\"_ or _\"clear\"_\n"
                + "3️⃣ Reply *Confirm* → share address → pick *UPI* or *COD*\n\n"
                + "🚚 *Delivery Rules:* Min order ₹" + formatMoney(rules.minOrderValue())
                + " | Fee ₹" + formatMoney(rules.deliveryFee())
                + " (FREE above ₹" + formatMoney(rules.freeDeliveryAbove()) + ")";
    }

    private String buildCatalogSummaryReply() {
        List<CatalogProduct> products = catalogService.getAllProducts();
        StringBuilder sb = new StringBuilder("🛍️ *").append(props.getStoreName()).append(" — Popular Items:*\n");
        products.stream()
                .filter(p -> p.getStock() > 0)
                .limit(12)
                .forEach(p -> sb.append("• ").append(p.getName())
                        .append(" — *₹").append(formatMoney(p.getPrice())).append("*\n"));
        sb.append("\n💬 Text any items + quantities in Hinglish to add them to your cart!");
        return sb.toString();
    }

    private String buildBillSummaryReply(List<CartItemEntry> cart, ChatSession session, StoreRulesDto rules, List<CatalogMatchResult> matches) {
        StringBuilder sb = new StringBuilder();
        List<CatalogMatchResult> issues = matches.stream()
                .filter(m -> !m.matched() || m.outOfStock())
                .toList();

        if (!issues.isEmpty()) {
            for (CatalogMatchResult issue : issues) {
                if (issue.outOfStock()) {
                    sb.append("⚠️ *").append(issue.matchedProductName()).append("* is currently out of stock.");
                } else {
                    sb.append("❓ Could not find _\"").append(issue.rawQuery()).append("\"_ in catalog.");
                }
                if (issue.alternatives() != null && !issue.alternatives().isEmpty()) {
                    sb.append(" Try: ").append(String.join(", ", issue.alternatives()));
                }
                sb.append("\n");
            }
            sb.append("\n");
        }

        if (cart.isEmpty()) {
            sb.append("🛒 Your cart is currently empty. Reply _\"Catalog dikhao\"_ to see available products.");
            return sb.toString();
        }

        BillTotals totals = computeTotals(cart, rules);
        sb.append("🧾 *Your Itemized Bill — ").append(props.getStoreName()).append("*\n");
        sb.append("--------------------------------\n");
        int idx = 1;
        for (CartItemEntry item : cart) {
            sb.append(idx++).append(". ").append(item.quantity()).append("x ")
                    .append(item.productName())
                    .append(" — ₹").append(formatMoney(item.lineTotal())).append("\n");
        }
        sb.append("--------------------------------\n");
        sb.append("Subtotal: ₹").append(formatMoney(totals.subtotal())).append("\n");
        if (totals.deliveryFee().compareTo(BigDecimal.ZERO) == 0) {
            sb.append("Delivery Fee: *FREE* 🎉\n");
        } else {
            sb.append("Delivery Fee: ₹").append(formatMoney(totals.deliveryFee())).append("\n");
        }
        sb.append("*Total Payable: ₹").append(formatMoney(totals.total())).append("*\n");

        if (totals.amountForFreeDelivery().compareTo(BigDecimal.ZERO) > 0) {
            sb.append("💡 _Add ₹").append(formatMoney(totals.amountForFreeDelivery()))
                    .append(" more for FREE delivery!_\n");
        }
        if (totals.subtotal().compareTo(rules.minOrderValue()) < 0) {
            BigDecimal needed = rules.minOrderValue().subtract(totals.subtotal());
            sb.append("\n⚠️ Add ₹").append(formatMoney(needed))
                    .append(" more to meet the ₹").append(formatMoney(rules.minOrderValue())).append(" minimum order.");
        } else if (session.getDeliveryAddress() != null && !session.getDeliveryAddress().isBlank()) {
            sb.append("\n📍 Delivery Address: *").append(session.getDeliveryAddress()).append("*\n");
            sb.append("Reply *UPI* to pay online or *COD* for Cash on Delivery.");
        } else {
            sb.append("\nReply *Confirm* to proceed (or send your delivery address directly!).");
        }

        return sb.toString();
    }

    private String buildOrderHistoryReply(ChatSession session) {
        List<BotOrder> orders = orderRepo.findByPhoneOrderByCreatedAtDesc(session.getPhone());
        if (orders.isEmpty()) {
            return "📭 No past orders found for this WhatsApp number. Send a grocery list to place your first order!";
        }
        StringBuilder sb = new StringBuilder("📜 *Your Recent Orders:*\n");
        orders.stream().limit(5).forEach(o -> sb.append("• *").append(o.getOrderCode()).append("* — ")
                .append(o.getStatus().name().replace('_', ' '))
                .append(" • ₹").append(formatMoney(o.getTotal()))
                .append(" (").append(o.getItems().size()).append(" items)\n"));
        sb.append("\nReply *Repeat last order* to reorder your most recent basket, or send *status DRC-WA-XXXX*.");
        return sb.toString();
    }

    private String handleReorder(List<CartItemEntry> cart, ChatSession session, List<CatalogProduct> catalog, StoreRulesDto rules) {
        List<BotOrder> orders = orderRepo.findByPhoneOrderByCreatedAtDesc(session.getPhone());
        if (orders.isEmpty()) {
            return "📭 No previous order found to repeat. Send a grocery list like _\"2 Amul milk aur 1 brown bread\"_!";
        }
        BotOrder lastOrder = orders.get(0);
        Map<Long, CatalogProduct> byId = new HashMap<>();
        for (CatalogProduct p : catalog) {
            if (p.getId() != null) byId.put(p.getId(), p);
        }

        List<String> skipped = new ArrayList<>();
        int addedCount = 0;
        for (BotOrderItem item : lastOrder.getItems()) {
            CatalogProduct live = item.getProductId() != null ? byId.get(item.getProductId()) : null;
            if (live == null) {
                live = catalog.stream()
                        .filter(p -> p.getName().equalsIgnoreCase(item.getProductName()))
                        .findFirst()
                        .orElse(null);
            }
            if (live == null || live.getStock() <= 0) {
                skipped.add(item.getProductName());
                continue;
            }
            int qty = Math.min(item.getQuantity(), live.getStock());
            CatalogMatchResult match = new CatalogMatchResult(
                    item.getRawQuery() != null ? item.getRawQuery() : item.getProductName(),
                    qty, true, false, live.getId(), live.getName(),
                    live.getPrice(), live.getUnit(), live.getStock(), 1.0, "Reordered from " + lastOrder.getOrderCode(), List.of()
            );
            mergeIntoCart(cart, match, qty);
            addedCount++;
        }

        if (addedCount == 0) {
            return "⚠️ Sorry, items from order *" + lastOrder.getOrderCode() + "* are currently out of stock. Reply _\"Catalog dikhao\"_ to pick fresh items.";
        }

        if ((session.getDeliveryAddress() == null || session.getDeliveryAddress().isBlank())
                && lastOrder.getDeliveryAddress() != null && !lastOrder.getDeliveryAddress().isBlank()) {
            session.setDeliveryAddress(lastOrder.getDeliveryAddress());
        }
        saveCart(session, cart);
        session.setState(ConversationState.REVIEWING_CART);

        String prefix = "🔁 Loaded items from *" + lastOrder.getOrderCode() + "*!\n"
                + (skipped.isEmpty() ? "" : "⚠️ Out of stock & skipped: " + String.join(", ", skipped) + "\n")
                + "\n";
        return prefix + buildBillSummaryReply(cart, session, rules, List.of());
    }

    private String buildOrderStatusReply(ChatSession session, String rawMessage) {
        if (rawMessage != null) {
            Matcher m = ORDER_CODE_PATTERN.matcher(rawMessage);
            if (m.find()) {
                String requestedCode = m.group(1).toUpperCase(Locale.ROOT);
                Optional<BotOrder> specific = orderRepo.findByOrderCode(requestedCode)
                        .filter(o -> o.getPhone().equals(session.getPhone()));
                if (specific.isPresent()) {
                    return formatOrderStatusCard(specific.get());
                }
                return "❓ Could not find order *" + requestedCode + "* for this WhatsApp number.";
            }
        }

        List<BotOrder> orders = orderRepo.findByPhoneOrderByCreatedAtDesc(session.getPhone());
        if (orders.isEmpty()) {
            return "📭 No orders placed yet from this WhatsApp number. Send a grocery list to place your first order!";
        }
        return formatOrderStatusCard(orders.get(0));
    }

    private String formatOrderStatusCard(BotOrder order) {
        return "📦 *Order Tracking — " + order.getOrderCode() + "*\n"
                + "• Status: *" + order.getStatus().name().replace('_', ' ') + "*\n"
                + "• Payment: " + order.getPaymentMethod() + " (" + order.getPaymentStatus() + ")\n"
                + "• Total: ₹" + formatMoney(order.getTotal()) + "\n"
                + "• Address: " + order.getDeliveryAddress() + "\n"
                + "• 🔐 Delivery OTP: *" + order.getDeliveryOtp() + "*";
    }

    private List<String> buildCartQuickReplies(ChatSession session) {
        if (session.getDeliveryAddress() != null && !session.getDeliveryAddress().isBlank()) {
            return List.of("Pay via UPI", "Cash on Delivery (COD)", "Clear cart");
        }
        return List.of("Confirm Order", "Flat 402, Krishna Heights, Sector 12", "Clear cart");
    }

    private BillTotals computeTotals(List<CartItemEntry> cart, StoreRulesDto rules) {
        BigDecimal subtotal = cart.stream()
                .map(CartItemEntry::lineTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        if (subtotal.compareTo(BigDecimal.ZERO) == 0) {
            return new BillTotals(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, rules.freeDeliveryAbove());
        }

        boolean freeDelivery = subtotal.compareTo(rules.freeDeliveryAbove()) >= 0;
        BigDecimal deliveryFee = freeDelivery ? BigDecimal.ZERO : rules.deliveryFee();
        BigDecimal total = subtotal.add(deliveryFee);
        BigDecimal remainingForFree = freeDelivery
                ? BigDecimal.ZERO
                : rules.freeDeliveryAbove().subtract(subtotal).max(BigDecimal.ZERO);

        return new BillTotals(subtotal, deliveryFee, total, remainingForFree);
    }

    private ChatSession createSession(String phone, String customerName) {
        ChatSession s = new ChatSession();
        s.setPhone(phone);
        s.setCustomerName(customerName != null && !customerName.isBlank() ? customerName.trim() : "WhatsApp Customer");
        s.setState(ConversationState.IDLE);
        s.setCartJson("[]");
        s.setUpdatedAt(LocalDateTime.now());
        return sessionRepo.save(s);
    }

    private List<CartItemEntry> readCart(ChatSession session) {
        try {
            if (session.getCartJson() == null || session.getCartJson().isBlank()) {
                return new ArrayList<>();
            }
            return mapper.readValue(session.getCartJson(), new TypeReference<>() {});
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private void saveCart(ChatSession session, List<CartItemEntry> cart) {
        try {
            session.setCartJson(mapper.writeValueAsString(cart));
        } catch (Exception e) {
            session.setCartJson("[]");
        }
    }

    public String normalizePhone(String raw) {
        if (raw == null || raw.isBlank()) return "+919876543210";
        String cleaned = raw.replace("whatsapp:", "").replaceAll("[\\s-]", "").trim();
        if (cleaned.startsWith("+")) {
            return cleaned;
        }
        if (cleaned.matches("^[6-9]\\d{9}$")) {
            return "+91" + cleaned;
        }
        return "+" + cleaned;
    }

    private String generateOrderCode() {
        for (int attempt = 0; attempt < 15; attempt++) {
            String candidate = "DRC-WA-" + (1000 + RANDOM.nextInt(9000));
            if (orderRepo.findByOrderCode(candidate).isEmpty()) {
                return candidate;
            }
        }
        return "DRC-WA-" + (100000 + RANDOM.nextInt(900000));
    }

    private String formatMoney(BigDecimal val) {
        return val.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private record BillTotals(
            BigDecimal subtotal,
            BigDecimal deliveryFee,
            BigDecimal total,
            BigDecimal amountForFreeDelivery
    ) {}
}


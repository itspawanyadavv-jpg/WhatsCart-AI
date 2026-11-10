package com.drc.whatsappbot;

import com.drc.whatsappbot.dto.ChatTurnResponse;
import com.drc.whatsappbot.enums.BotOrderStatus;
import com.drc.whatsappbot.enums.BotPaymentStatus;
import com.drc.whatsappbot.enums.ConversationState;
import com.drc.whatsappbot.enums.MessageChannel;
import com.drc.whatsappbot.model.BotOrder;
import com.drc.whatsappbot.model.CatalogProduct;
import com.drc.whatsappbot.repository.CatalogProductRepository;
import com.drc.whatsappbot.service.ConversationEngineService;
import com.drc.whatsappbot.service.DrcCatalogSyncService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class ConversationEngineIntegrationTest {

    @Autowired
    private ConversationEngineService engine;

    @Autowired
    private DrcCatalogSyncService catalogService;

    @Autowired
    private CatalogProductRepository catalogRepo;

    @BeforeEach
    void initCatalog() {
        catalogService.seedFallbackCatalog(true);
    }

    @Test
    void fullEndToEndHinglishCheckoutAndStatusAlertFlow() {
        String phone = "+919988776655";
        engine.resetSession(phone, "Aman");

        // 1. Send Hinglish grocery list
        ChatTurnResponse turn1 = engine.handleInboundMessage(
                phone, "Aman",
                "Bhaiya 2 packet Amul milk, 1 brown bread, aur 5kg Aashirvaad atta deliver kar do",
                MessageChannel.SIMULATOR
        );

        assertThat(turn1.state()).isEqualTo(ConversationState.REVIEWING_CART);
        assertThat(turn1.cart()).hasSize(3);
        // 2 * 28 (56) + 1 * 50 (50) + 1 * 265 (265) = 371.00 subtotal + 30.00 delivery = 401.00
        assertThat(turn1.subtotal()).isEqualByComparingTo(new BigDecimal("371.00"));
        assertThat(turn1.deliveryFee()).isEqualByComparingTo(new BigDecimal("30.00"));
        assertThat(turn1.total()).isEqualByComparingTo(new BigDecimal("401.00"));

        // 2. Confirm order -> prompts for address
        ChatTurnResponse turn2 = engine.handleInboundMessage(phone, "Aman", "Confirm", MessageChannel.SIMULATOR);
        assertThat(turn2.state()).isEqualTo(ConversationState.AWAITING_ADDRESS);

        // 3. Provide address -> prompts for payment
        ChatTurnResponse turn3 = engine.handleInboundMessage(phone, "Aman", "Flat 402, Krishna Heights, Sector 12", MessageChannel.SIMULATOR);
        assertThat(turn3.state()).isEqualTo(ConversationState.AWAITING_PAYMENT);
        assertThat(turn3.deliveryAddress()).isEqualTo("Flat 402, Krishna Heights, Sector 12");

        // 4. Choose UPI -> creates order + local checkout URL + UPI intent URI
        ChatTurnResponse turn4 = engine.handleInboundMessage(phone, "Aman", "UPI", MessageChannel.SIMULATOR);
        assertThat(turn4.state()).isEqualTo(ConversationState.IDLE);
        assertThat(turn4.activeOrder()).isNotNull();
        assertThat(turn4.activeOrder().getPaymentStatus()).isEqualTo(BotPaymentStatus.PENDING);
        assertThat(turn4.activeOrder().getUpiDeepLink()).contains("upi://pay");
        assertThat(turn4.activeOrder().getRazorpayPaymentLinkUrl())
                .contains("/api/payments/checkout?orderCode=" + turn4.activeOrder().getOrderCode());

        // 5. Complete UPI payment -> order confirmed + WhatsApp notification pushed
        String orderCode = turn4.activeOrder().getOrderCode();
        BotOrder paid = engine.markPaymentSucceeded(orderCode, "pay_test_123");
        assertThat(paid.getPaymentStatus()).isEqualTo(BotPaymentStatus.PAID);
        assertThat(paid.getStatus()).isEqualTo(BotOrderStatus.CONFIRMED);

        // 6. Merchant dispatches Out for Delivery -> WhatsApp alert sent with OTP
        BotOrder dispatched = engine.updateOrderStatus(orderCode, BotOrderStatus.OUT_FOR_DELIVERY, "Suresh");
        assertThat(dispatched.getStatus()).isEqualTo(BotOrderStatus.OUT_FOR_DELIVERY);

        ChatTurnResponse afterAlert = engine.getSessionState(phone);
        assertThat(afterAlert.messages().get(afterAlert.messages().size() - 1).getBody())
                .contains("Out for Delivery")
                .contains("Suresh")
                .contains(dispatched.getDeliveryOtp());

        // 7. Invalid OTP rejected; valid OTP delivers order and blocks further status transitions
        assertThatThrownBy(() -> engine.updateOrderStatus(orderCode, BotOrderStatus.DELIVERED, "Suresh", "0000"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Invalid delivery OTP");

        BotOrder delivered = engine.updateOrderStatus(orderCode, BotOrderStatus.DELIVERED, "Suresh", dispatched.getDeliveryOtp());
        assertThat(delivered.getStatus()).isEqualTo(BotOrderStatus.DELIVERED);

        assertThatThrownBy(() -> engine.updateOrderStatus(orderCode, BotOrderStatus.PACKING, "Suresh"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void deductsAndRestoresStockAndSupportsPartialRemoveAndMinOrderValidation() {
        String phone = "+919900112233";
        engine.resetSession(phone, "Vikram");

        CatalogProduct milkBefore = catalogRepo.findByNameIgnoreCase("Amul Taaza Milk 500 ml").orElseThrow();
        int initialMilkStock = milkBefore.getStock();

        // 1. Add 1 Parle-G (₹70 < ₹100 min order) -> Confirm blocked
        engine.handleInboundMessage(phone, "Vikram", "1 Parle-G", MessageChannel.SIMULATOR);
        ChatTurnResponse blockedConfirm = engine.handleInboundMessage(phone, "Vikram", "Confirm", MessageChannel.SIMULATOR);
        assertThat(blockedConfirm.state()).isEqualTo(ConversationState.REVIEWING_CART);
        assertThat(blockedConfirm.messages().get(blockedConfirm.messages().size() - 1).getBody())
                .contains("Minimum order value");

        // 2. Add 3 Amul milk -> update to 4 -> remove 2 Amul milk -> verify 2 remain in cart (₹70 + ₹56 = ₹126 >= ₹100)
        engine.handleInboundMessage(phone, "Vikram", "3 packet Amul milk", MessageChannel.SIMULATOR);
        ChatTurnResponse afterUpdate = engine.handleInboundMessage(phone, "Vikram", "make Amul milk 4", MessageChannel.SIMULATOR);
        assertThat(afterUpdate.cart())
                .anySatisfy(item -> {
                    assertThat(item.productName()).contains("Amul Taaza Milk");
                    assertThat(item.quantity()).isEqualTo(4);
                });

        ChatTurnResponse afterPartialRemove = engine.handleInboundMessage(phone, "Vikram", "remove 2 Amul milk", MessageChannel.SIMULATOR);
        assertThat(afterPartialRemove.cart())
                .anySatisfy(item -> {
                    assertThat(item.productName()).contains("Amul Taaza Milk");
                    assertThat(item.quantity()).isEqualTo(2);
                });

        // 3. Checkout COD -> stock decremented by 2
        engine.handleInboundMessage(phone, "Vikram", "Flat 12, Sector 4", MessageChannel.SIMULATOR);
        ChatTurnResponse cod = engine.handleInboundMessage(phone, "Vikram", "COD", MessageChannel.SIMULATOR);
        assertThat(cod.activeOrder()).isNotNull();
        assertThat(cod.activeOrder().getPaymentStatus()).isEqualTo(BotPaymentStatus.COD);
        assertThat(cod.activeOrder().getStatus()).isEqualTo(BotOrderStatus.CONFIRMED);

        int stockAfterOrder = catalogRepo.findByNameIgnoreCase("Amul Taaza Milk 500 ml").orElseThrow().getStock();
        assertThat(stockAfterOrder).isEqualTo(initialMilkStock - 2);

        // 4. Customer cancels order -> stock restored
        ChatTurnResponse cancelled = engine.handleInboundMessage(phone, "Vikram", "cancel order", MessageChannel.SIMULATOR);
        assertThat(cancelled.messages().get(cancelled.messages().size() - 1).getBody())
                .contains("Order Cancelled");

        int stockAfterCancel = catalogRepo.findByNameIgnoreCase("Amul Taaza Milk 500 ml").orElseThrow().getStock();
        assertThat(stockAfterCancel).isEqualTo(initialMilkStock);

        // 5. Order history and Repeat last order reload items cleanly
        ChatTurnResponse history = engine.handleInboundMessage(phone, "Vikram", "my orders", MessageChannel.SIMULATOR);
        assertThat(history.messages().get(history.messages().size() - 1).getBody())
                .contains(cod.activeOrder().getOrderCode())
                .contains("CANCELLED");

        ChatTurnResponse reordered = engine.handleInboundMessage(phone, "Vikram", "repeat last order", MessageChannel.SIMULATOR);
        assertThat(reordered.state()).isEqualTo(ConversationState.REVIEWING_CART);
        assertThat(reordered.cart()).hasSize(2);
    }

    @Test
    void removesCartItemsUsingHinglishSynonyms() {
        String phone = "+919900445566";
        engine.resetSession(phone, "Sonia");

        engine.handleInboundMessage(phone, "Sonia", "2 Amul milk aur 2 Tata salt", MessageChannel.SIMULATOR);
        ChatTurnResponse partial = engine.handleInboundMessage(phone, "Sonia", "remove 1 namak", MessageChannel.SIMULATOR);
        assertThat(partial.cart()).hasSize(2);
        assertThat(partial.cart())
                .anySatisfy(item -> {
                    assertThat(item.productName()).contains("Tata Salt");
                    assertThat(item.quantity()).isEqualTo(1);
                });

        ChatTurnResponse removedMilk = engine.handleInboundMessage(phone, "Sonia", "remove doodh", MessageChannel.SIMULATOR);
        assertThat(removedMilk.cart()).hasSize(1);
        assertThat(removedMilk.cart().get(0).productName()).contains("Tata Salt");

        ChatTurnResponse afterReset = engine.resetSession(phone, "Sonia");
        assertThat(afterReset.state()).isEqualTo(ConversationState.IDLE);
        assertThat(afterReset.cart()).isEmpty();
        assertThat(afterReset.messages()).hasSize(1);
    }
}


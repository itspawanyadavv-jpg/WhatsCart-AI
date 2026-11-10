package com.drc.whatsappbot.model;

import com.drc.whatsappbot.enums.ConversationState;
import jakarta.persistence.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "chat_sessions")
public class ChatSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String phone;

    private String customerName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ConversationState state = ConversationState.IDLE;

    @Lob
    @Column(columnDefinition = "TEXT")
    private String cartJson = "[]";

    @Column(length = 500)
    private String deliveryAddress;

    private String activeOrderCode;

    private LocalDateTime updatedAt = LocalDateTime.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }
    public String getCustomerName() { return customerName; }
    public void setCustomerName(String customerName) { this.customerName = customerName; }
    public ConversationState getState() { return state; }
    public void setState(ConversationState state) { this.state = state; }
    public String getCartJson() { return cartJson; }
    public void setCartJson(String cartJson) { this.cartJson = cartJson; }
    public String getDeliveryAddress() { return deliveryAddress; }
    public void setDeliveryAddress(String deliveryAddress) { this.deliveryAddress = deliveryAddress; }
    public String getActiveOrderCode() { return activeOrderCode; }
    public void setActiveOrderCode(String activeOrderCode) { this.activeOrderCode = activeOrderCode; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}


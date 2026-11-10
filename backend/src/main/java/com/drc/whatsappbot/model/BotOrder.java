package com.drc.whatsappbot.model;

import com.drc.whatsappbot.enums.BotOrderStatus;
import com.drc.whatsappbot.enums.BotPaymentStatus;
import com.drc.whatsappbot.enums.MessageChannel;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "bot_orders")
public class BotOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String orderCode;

    @Column(nullable = false)
    private String phone;

    private String customerName;

    @Column(nullable = false, length = 500)
    private String deliveryAddress;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private BotOrderStatus status = BotOrderStatus.PLACED;

    @Column(nullable = false)
    private String paymentMethod; // UPI_LINK or COD

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private BotPaymentStatus paymentStatus = BotPaymentStatus.PENDING;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal subtotal;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal deliveryFee;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal total;

    private String razorpayPaymentLinkId;
    private String razorpayPaymentLinkUrl;
    private String razorpayPaymentId;

    @Column(length = 800)
    private String upiDeepLink;

    @Enumerated(EnumType.STRING)
    private MessageChannel channel = MessageChannel.SIMULATOR;

    private String deliveryOtp;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    private List<BotOrderItem> items = new ArrayList<>();

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    private LocalDateTime updatedAt = LocalDateTime.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getOrderCode() { return orderCode; }
    public void setOrderCode(String orderCode) { this.orderCode = orderCode; }
    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }
    public String getCustomerName() { return customerName; }
    public void setCustomerName(String customerName) { this.customerName = customerName; }
    public String getDeliveryAddress() { return deliveryAddress; }
    public void setDeliveryAddress(String deliveryAddress) { this.deliveryAddress = deliveryAddress; }
    public BotOrderStatus getStatus() { return status; }
    public void setStatus(BotOrderStatus status) { this.status = status; }
    public String getPaymentMethod() { return paymentMethod; }
    public void setPaymentMethod(String paymentMethod) { this.paymentMethod = paymentMethod; }
    public BotPaymentStatus getPaymentStatus() { return paymentStatus; }
    public void setPaymentStatus(BotPaymentStatus paymentStatus) { this.paymentStatus = paymentStatus; }
    public BigDecimal getSubtotal() { return subtotal; }
    public void setSubtotal(BigDecimal subtotal) { this.subtotal = subtotal; }
    public BigDecimal getDeliveryFee() { return deliveryFee; }
    public void setDeliveryFee(BigDecimal deliveryFee) { this.deliveryFee = deliveryFee; }
    public BigDecimal getTotal() { return total; }
    public void setTotal(BigDecimal total) { this.total = total; }
    public String getRazorpayPaymentLinkId() { return razorpayPaymentLinkId; }
    public void setRazorpayPaymentLinkId(String razorpayPaymentLinkId) { this.razorpayPaymentLinkId = razorpayPaymentLinkId; }
    public String getRazorpayPaymentLinkUrl() { return razorpayPaymentLinkUrl; }
    public void setRazorpayPaymentLinkUrl(String razorpayPaymentLinkUrl) { this.razorpayPaymentLinkUrl = razorpayPaymentLinkUrl; }
    public String getRazorpayPaymentId() { return razorpayPaymentId; }
    public void setRazorpayPaymentId(String razorpayPaymentId) { this.razorpayPaymentId = razorpayPaymentId; }
    public String getUpiDeepLink() { return upiDeepLink; }
    public void setUpiDeepLink(String upiDeepLink) { this.upiDeepLink = upiDeepLink; }
    public MessageChannel getChannel() { return channel; }
    public void setChannel(MessageChannel channel) { this.channel = channel; }
    public String getDeliveryOtp() { return deliveryOtp; }
    public void setDeliveryOtp(String deliveryOtp) { this.deliveryOtp = deliveryOtp; }
    public List<BotOrderItem> getItems() { return items; }
    public void setItems(List<BotOrderItem> items) { this.items = items; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}


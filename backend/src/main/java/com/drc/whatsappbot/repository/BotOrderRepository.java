package com.drc.whatsappbot.repository;

import com.drc.whatsappbot.model.BotOrder;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface BotOrderRepository extends JpaRepository<BotOrder, Long> {
    Optional<BotOrder> findByOrderCode(String orderCode);
    Optional<BotOrder> findByRazorpayPaymentLinkId(String razorpayPaymentLinkId);
    List<BotOrder> findByPhoneOrderByCreatedAtDesc(String phone);
    List<BotOrder> findAllByOrderByCreatedAtDesc();
}


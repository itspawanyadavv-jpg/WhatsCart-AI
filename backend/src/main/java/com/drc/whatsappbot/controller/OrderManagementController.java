package com.drc.whatsappbot.controller;

import com.drc.whatsappbot.config.AdminAuthGuard;
import com.drc.whatsappbot.dto.OrderStatusUpdateRequest;
import com.drc.whatsappbot.model.BotOrder;
import com.drc.whatsappbot.repository.BotOrderRepository;
import com.drc.whatsappbot.service.ConversationEngineService;
import com.drc.whatsappbot.service.SseEventBusService;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

@RestController
@RequestMapping("/api/orders")
public class OrderManagementController {

    private final BotOrderRepository orderRepo;
    private final ConversationEngineService engine;
    private final SseEventBusService sseBus;
    private final AdminAuthGuard adminAuthGuard;

    public OrderManagementController(BotOrderRepository orderRepo,
                                     ConversationEngineService engine,
                                     SseEventBusService sseBus,
                                     AdminAuthGuard adminAuthGuard) {
        this.orderRepo = orderRepo;
        this.engine = engine;
        this.sseBus = sseBus;
        this.adminAuthGuard = adminAuthGuard;
    }

    @GetMapping
    public List<BotOrder> listOrders(@RequestHeader(value = "X-Admin-Key", required = false) String adminKey) {
        adminAuthGuard.requireAdminIfConfigured(adminKey);
        return orderRepo.findAllByOrderByCreatedAtDesc();
    }

    @GetMapping("/{orderCode}")
    public BotOrder getOrder(@PathVariable String orderCode,
                             @RequestHeader(value = "X-Admin-Key", required = false) String adminKey) {
        adminAuthGuard.requireAdminIfConfigured(adminKey);
        return orderRepo.findByOrderCode(orderCode)
                .orElseThrow(() -> new IllegalArgumentException("Order not found: " + orderCode));
    }

    @PostMapping("/{orderCode}/simulate-pay")
    public BotOrder simulatePayment(@PathVariable String orderCode,
                                    @RequestHeader(value = "X-Admin-Key", required = false) String adminKey) {
        adminAuthGuard.requireAdminIfConfigured(adminKey);
        adminAuthGuard.requireSimulatePayAllowed();
        return engine.markPaymentSucceeded(orderCode, "pay_demo_" + System.currentTimeMillis());
    }

    @PatchMapping("/{orderCode}/status")
    public BotOrder updateStatus(@PathVariable String orderCode,
                                 @RequestHeader(value = "X-Admin-Key", required = false) String adminKey,
                                 @Valid @RequestBody OrderStatusUpdateRequest request) {
        adminAuthGuard.requireAdminIfConfigured(adminKey);
        return engine.updateOrderStatus(orderCode, request.status(), request.riderName(), request.otp());
    }

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamMerchantOrders(@RequestHeader(value = "X-Admin-Key", required = false) String headerKey,
                                           @RequestParam(value = "adminKey", required = false) String queryKey) {
        adminAuthGuard.requireAdminIfConfigured(headerKey != null && !headerKey.isBlank() ? headerKey : queryKey);
        return sseBus.subscribe("merchant");
    }
}


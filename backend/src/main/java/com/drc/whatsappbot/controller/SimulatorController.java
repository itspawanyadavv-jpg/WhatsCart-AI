package com.drc.whatsappbot.controller;

import com.drc.whatsappbot.dto.ChatTurnResponse;
import com.drc.whatsappbot.dto.SimulatorMessageRequest;
import com.drc.whatsappbot.enums.MessageChannel;
import com.drc.whatsappbot.service.ConversationEngineService;
import com.drc.whatsappbot.service.SseEventBusService;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/simulator")
public class SimulatorController {

    private final ConversationEngineService engine;
    private final SseEventBusService sseBus;

    public SimulatorController(ConversationEngineService engine, SseEventBusService sseBus) {
        this.engine = engine;
        this.sseBus = sseBus;
    }

    @GetMapping("/session")
    public ChatTurnResponse getSession(@RequestParam(defaultValue = "+919876543210") String phone) {
        return engine.getSessionState(engine.normalizePhone(phone));
    }

    @PostMapping("/message")
    public ChatTurnResponse sendMessage(@Valid @RequestBody SimulatorMessageRequest request) {
        return engine.handleInboundMessage(
                request.phone(),
                request.customerName(),
                request.message(),
                MessageChannel.SIMULATOR
        );
    }

    @PostMapping("/reset")
    public ChatTurnResponse reset(@RequestParam(defaultValue = "+919876543210") String phone,
                                  @RequestParam(defaultValue = "Aman Sharma") String customerName) {
        return engine.resetSession(phone, customerName);
    }

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestParam(defaultValue = "+919876543210") String phone) {
        return sseBus.subscribe("chat:" + engine.normalizePhone(phone));
    }
}


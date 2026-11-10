package com.drc.whatsappbot.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Service
public class SseEventBusService {

    private static final Logger log = LoggerFactory.getLogger(SseEventBusService.class);
    private final Map<String, List<SseEmitter>> channels = new ConcurrentHashMap<>();

    public SseEmitter subscribe(String channelKey) {
        SseEmitter emitter = new SseEmitter(0L);
        channels.computeIfAbsent(channelKey, k -> new CopyOnWriteArrayList<>()).add(emitter);

        emitter.onCompletion(() -> remove(channelKey, emitter));
        emitter.onTimeout(() -> remove(channelKey, emitter));
        emitter.onError(ex -> remove(channelKey, emitter));

        try {
            emitter.send(SseEmitter.event().name("connected").data(Map.of("channel", channelKey)));
        } catch (IOException e) {
            remove(channelKey, emitter);
        }
        return emitter;
    }

    public void publish(String channelKey, String eventName, Object payload) {
        List<SseEmitter> list = channels.get(channelKey);
        if (list == null || list.isEmpty()) {
            return;
        }
        for (SseEmitter emitter : list) {
            try {
                emitter.send(SseEmitter.event().name(eventName).data(payload));
            } catch (IOException | IllegalStateException e) {
                log.debug("Removing closed SSE emitter for channel {}: {}", channelKey, e.getMessage());
                remove(channelKey, emitter);
            }
        }
    }

    private void remove(String channelKey, SseEmitter emitter) {
        List<SseEmitter> list = channels.get(channelKey);
        if (list != null) {
            list.remove(emitter);
        }
    }
}


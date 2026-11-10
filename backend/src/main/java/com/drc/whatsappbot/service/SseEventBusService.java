package com.drc.whatsappbot.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
public class SseEventBusService implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(SseEventBusService.class);
    private final Map<String, List<SseEmitter>> channels = new ConcurrentHashMap<>();
    private final ExecutorService dispatchExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "sse-dispatch");
        t.setDaemon(true);
        return t;
    });

    public SseEmitter subscribe(String channelKey) {
        SseEmitter emitter = new SseEmitter(0L);
        channels.computeIfAbsent(channelKey, k -> new CopyOnWriteArrayList<>()).add(emitter);

        emitter.onCompletion(() -> remove(channelKey, emitter));
        emitter.onTimeout(() -> remove(channelKey, emitter));
        emitter.onError(ex -> remove(channelKey, emitter));

        try {
            emitter.send(SseEmitter.event().name("connected").data(Map.of("channel", channelKey)));
        } catch (Exception e) {
            remove(channelKey, emitter);
        }
        return emitter;
    }

    public void publish(String channelKey, String eventName, Object payload) {
        List<SseEmitter> list = channels.get(channelKey);
        if (list == null || list.isEmpty()) {
            return;
        }
        List<SseEmitter> snapshot = List.copyOf(list);
        dispatchExecutor.execute(() -> dispatchEvent(channelKey, snapshot, eventName, payload));
    }

    @Scheduled(fixedDelay = 25000, initialDelay = 25000)
    public void sendHeartbeats() {
        if (channels.isEmpty()) {
            return;
        }
        channels.forEach((channelKey, list) -> {
            if (list == null || list.isEmpty()) {
                return;
            }
            List<SseEmitter> snapshot = List.copyOf(list);
            dispatchExecutor.execute(() -> {
                for (SseEmitter emitter : snapshot) {
                    try {
                        emitter.send(SseEmitter.event().comment("keepalive"));
                    } catch (Exception e) {
                        log.debug("Removing inactive SSE emitter on heartbeat for {}: {}", channelKey, e.getMessage());
                        remove(channelKey, emitter);
                    }
                }
            });
        });
    }

    public void dispatchEvent(String channelKey, List<SseEmitter> emitters, String eventName, Object payload) {
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().name(eventName).data(payload));
            } catch (Exception e) {
                log.debug("Removing closed SSE emitter for channel {}: {}", channelKey, e.getMessage());
                remove(channelKey, emitter);
            }
        }
    }

    private void remove(String channelKey, SseEmitter emitter) {
        List<SseEmitter> list = channels.get(channelKey);
        if (list != null) {
            list.remove(emitter);
            if (list.isEmpty()) {
                channels.remove(channelKey, list);
            }
        }
    }

    @Override
    public void destroy() {
        dispatchExecutor.shutdownNow();
    }
}


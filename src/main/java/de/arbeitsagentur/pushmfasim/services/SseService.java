package de.arbeitsagentur.pushmfasim.services;

import de.arbeitsagentur.pushmfasim.model.FcmMessageRequestMessage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * SmartLifecycle is needed to gracefully shutdown server sent events.
 * @PreDestroy is not sufficient because it runs after gracefull shutdown.
 */
@Service
public class SseService implements SmartLifecycle {
    private final Logger LOG = org.slf4j.LoggerFactory.getLogger(SseService.class);
    // list of SseEmitters could be added here if needed for broadcasting
    private final List<SseEmitter> emitters = new ArrayList<>();
    private final ExecutorService executorService = Executors.newCachedThreadPool();
    private boolean running = false;
    private static final long HEARTBEAT_INTERVAL_MS = 10000;
    public static final long MESSAGE_SEND_TIMEOUT_MS = 360000;

    @SuppressWarnings("null")
    public void sendMessageToAllEmitters(FcmMessageRequestMessage request) {
        executorService.execute(() -> {
            synchronized (emitters) {
                for (SseEmitter emitter : emitters) {
                    if (!running) {
                        break;
                    }
                    try {
                        emitter.send(SseEmitter.event().name("fcm-message").data(request));
                    } catch (IOException e) {
                        LOG.error("Error sending message to emitter: {}", e.getMessage());
                    }
                }
            }
            // work done, end thread
            Thread.currentThread().interrupt();
        });
    }

    public SseEmitter createSseEmitter() {
        if (!running) {
            return null;
        }
        SseEmitter sseEmitter = new SseEmitter(MESSAGE_SEND_TIMEOUT_MS);
        sseEmitter.onCompletion(() -> removeEmitter(sseEmitter));
        sseEmitter.onTimeout(() -> removeEmitter(sseEmitter));
        synchronized (emitters) {
            emitters.add(sseEmitter);
        }

        return sseEmitter;
    }

    public void sendHeartbeat() {
        executorService.execute(() -> {
            while (running) {
                synchronized (emitters) {
                    for (SseEmitter emitter : emitters) {
                        if (!running) {
                            break;
                        }
                        try {
                            emitter.send(SseEmitter.event().name("heartbeat").data("ping"));
                        } catch (IOException e) {
                            LOG.error("Error sending heartbeat to emitter: {}", e.getMessage());
                        }
                    }
                }
                try {
                    Thread.sleep(HEARTBEAT_INTERVAL_MS);
                } catch (InterruptedException e) {
                    LOG.warn("Heartbeat thread interrupted, stopping heartbeat");
                    break;
                }
            }

            // work done, end thread
            Thread.currentThread().interrupt();
        });
    }

    private void removeEmitter(SseEmitter emitter) {
        synchronized (emitters) {
            emitters.remove(emitter);
        }
    }

    private void doShutdown() {
        running = false;
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(5, TimeUnit.SECONDS)) {
                List<Runnable> unfinished = executorService.shutdownNow();
                LOG.warn("SseService unfinished thread count {}", unfinished.size());
            }
        } catch (InterruptedException e) {
            executorService.shutdownNow();
        }
        synchronized (emitters) {
            for (SseEmitter emitter : emitters) {
                emitter.complete();
            }
        }
        LOG.debug("SseService terminated");
    }

    @Override
    public void start() {
        running = true;
        sendHeartbeat();
    }

    @Override
    public void stop() {
        running = false;
    }

    @Override
    public void stop(@SuppressWarnings("null") Runnable callback) {
        new Thread(() -> {
                    try {
                        doShutdown();
                    } finally {
                        running = false;
                        callback.run();
                    }
                })
                .start();
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}

package de.arbeitsagentur.pushmfasim.services;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import de.arbeitsagentur.pushmfasim.model.FcmMessageRequestMessage;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class SseServiceTest {

    private SseService sseService;

    @BeforeEach
    void setUp() {
        sseService = new SseService();
        sseService.start();
    }

    @AfterEach
    void tearDown() {
        sseService.stop();
    }

    @Test
    void start_shouldSetRunningToTrue() throws Exception {
        Field runningField = SseService.class.getDeclaredField("running");
        runningField.setAccessible(true);
        boolean runningValue = (boolean) runningField.get(sseService);

        assertTrue(runningValue, "Running should be true after start");
    }

    @Test
    void stop_shouldSetRunningToFalse() throws Exception {
        sseService.stop();
        Field runningField = SseService.class.getDeclaredField("running");
        runningField.setAccessible(true);
        boolean runningValue = (boolean) runningField.get(sseService);

        assertFalse(runningValue, "Running should be false after stop");
    }

    @Test
    void start_shouldStartHeartbeatThread() throws Exception {
        // Spy on the SseService to verify that sendHeartbeat is called
        SseService spyService = spy(sseService);
        spyService.start();

        // Verify that sendHeartbeat was called once during start
        verify(spyService, times(1)).sendHeartbeat();
    }

    @Test
    void createSseEmitter_shouldReturnEmitterWithMaxTimeout() {
        SseEmitter emitter = sseService.createSseEmitter();

        assertNotNull(emitter);
        assertEquals(SseService.MESSAGE_SEND_TIMEOUT_MS, emitter.getTimeout());
    }

    @Test
    void createSseEmitter_shouldAddEmitterToList() throws Exception {
        SseEmitter emitter1 = sseService.createSseEmitter();
        SseEmitter emitter2 = sseService.createSseEmitter();

        assertNotNull(emitter1);
        assertNotNull(emitter2);

        Field emittersField = SseService.class.getDeclaredField("emitters");
        emittersField.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<SseEmitter> emitters = (List<SseEmitter>) emittersField.get(sseService);

        assertTrue(emitters.contains(emitter1));
        assertTrue(emitters.contains(emitter2));
    }

    @SuppressWarnings("null")
    @Test
    void sendMessageToAllEmitters_shouldSendToAllEmitters() throws Exception {
        FcmMessageRequestMessage request = new FcmMessageRequestMessage();

        SseEmitter emitter1 = spy(new SseEmitter(1000L));
        SseEmitter emitter2 = spy(new SseEmitter(1000L));

        // Set the spies in the service
        Field emittersField = SseService.class.getDeclaredField("emitters");
        emittersField.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<SseEmitter> emitters = (List<SseEmitter>) emittersField.get(sseService);
        emitters.add(emitter1);
        emitters.add(emitter2);

        sseService.sendMessageToAllEmitters(request);

        Thread.sleep(200); // Give async execution time to complete

        verify(emitter1, times(1)).send(any(SseEmitter.SseEventBuilder.class));
        verify(emitter2, times(1)).send(any(SseEmitter.SseEventBuilder.class));
    }

    @SuppressWarnings("null")
    @Test
    void sendMessageToAllEmitters_withIOException_shouldLogError() throws Exception {
        // Create a spy emitter that will throw IOException
        SseEmitter spyEmitter = spy(new SseEmitter(Long.MAX_VALUE));
        doThrow(new IOException("Test exception")).when(spyEmitter).send(any(SseEmitter.SseEventBuilder.class));

        // Create a normal emitter that should still work
        SseEmitter normalEmitter = spy(new SseEmitter(Long.MAX_VALUE));

        // Inject the spy emitters into the service's emitter list using reflection
        Field emittersField = SseService.class.getDeclaredField("emitters");
        emittersField.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<SseEmitter> emitters = (List<SseEmitter>) emittersField.get(sseService);
        emitters.clear();
        emitters.add(spyEmitter);
        emitters.add(normalEmitter);

        FcmMessageRequestMessage request = new FcmMessageRequestMessage();
        sseService.sendMessageToAllEmitters(request);

        Thread.sleep(300); // Give async execution time to complete

        // Verify that send was called on the failing emitter
        verify(spyEmitter, times(1)).send(any(SseEmitter.SseEventBuilder.class));

        // Verify that the normal emitter still received the message despite the error
        verify(normalEmitter, times(1)).send(any(SseEmitter.SseEventBuilder.class));

        // Verify the method completed without throwing exception
        assertDoesNotThrow(() -> sseService.sendMessageToAllEmitters(request));
    }

    @Test
    void sendMessageToAllEmitters_shouldExecuteAsynchronously() throws Exception {
        FcmMessageRequestMessage request = new FcmMessageRequestMessage();
        long startTime = System.currentTimeMillis();

        sseService.sendMessageToAllEmitters(request);

        long endTime = System.currentTimeMillis();

        assertTrue(endTime - startTime < 100, "Method should return immediately without blocking");
    }
}

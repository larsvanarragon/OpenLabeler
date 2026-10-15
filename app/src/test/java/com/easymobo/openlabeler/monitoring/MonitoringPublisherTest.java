/*
 * Copyright (c) 2024. Kin-Hong Wong. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.easymobo.openlabeler.monitoring;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MonitoringPublisherTest {
    @Test
    void disabledPublisherDoesNotStartWork() throws Exception {
        MonitoringPublisher publisher = new MonitoringPublisher(MonitoringPublisher.Config.builder()
                .settings(MonitorSettings.disabled())
                .build());
        try {
            long started = System.nanoTime();
            assertFalse(publisher.isEnabled());
            assertFalse(publisher.publish(sample("ImageLoaded")));
            assertEquals(0, publisher.droppedCount());
            assertTrue(publisher.awaitIdle(Duration.ofSeconds(1)));
            assertTrue(Duration.ofNanos(System.nanoTime() - started).toMillis() < 500);
        }
        finally {
            publisher.close();
        }
    }

    @Test
    void publishReturnsWhileRequestIsStillBlocked() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        List<String> bodies = new CopyOnWriteArrayList<>();
        HttpServer server = server((exchange, index) -> {
            entered.countDown();
            release.await(5, TimeUnit.SECONDS);
            byte[] body = exchange.getRequestBody().readAllBytes();
            bodies.add(new String(body, StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        MonitoringPublisher publisher = publisher(server, 8, Duration.ofSeconds(2), Duration.ofSeconds(30));
        try {
            long started = System.nanoTime();
            assertTrue(publisher.publish(sample("AnnotationSaved")));
            long elapsedMs = Duration.ofNanos(System.nanoTime() - started).toMillis();
            assertTrue(elapsedMs < 500, "publish blocked the caller for " + elapsedMs + "ms");
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            release.countDown();
            assertTrue(publisher.awaitIdle(Duration.ofSeconds(2)));
            assertEquals(1, bodies.size());
            assertTrue(bodies.get(0).contains("\"event\":\"AnnotationSaved\""));
        }
        finally {
            release.countDown();
            publisher.close();
            server.stop(0);
        }
    }

    @Test
    void fullQueueDropsWithoutBlocking() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        HttpServer server = server((exchange, index) -> {
            entered.countDown();
            release.await(5, TimeUnit.SECONDS);
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        MonitoringPublisher publisher = publisher(server, 1, Duration.ofSeconds(2), Duration.ofSeconds(30));
        try {
            assertTrue(publisher.publish(sample("ImageLoaded")));
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            assertTrue(publisher.publish(sample("ModelLoaded")));
            long started = System.nanoTime();
            assertFalse(publisher.publish(sample("HintAccepted")));
            long elapsedMs = Duration.ofNanos(System.nanoTime() - started).toMillis();
            assertTrue(elapsedMs < 200, "drop blocked the caller for " + elapsedMs + "ms");
            assertTrue(publisher.droppedCount() >= 1);
        }
        finally {
            release.countDown();
            publisher.close();
            server.stop(0);
        }
    }

    @Test
    void shortTimeoutDoesNotStallLaterEvents() throws Exception {
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger handled = new AtomicInteger();
        List<String> bodies = new CopyOnWriteArrayList<>();
        HttpServer server = server((exchange, index) -> {
            int call = handled.incrementAndGet();
            if (call == 1) {
                firstEntered.countDown();
                release.await(5, TimeUnit.SECONDS);
            }
            byte[] body = exchange.getRequestBody().readAllBytes();
            bodies.add(new String(body, StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        MonitoringPublisher publisher = publisher(server, 8, Duration.ofMillis(300), Duration.ofSeconds(30));
        try {
            assertTrue(publisher.publish(sample("InferenceStarted")));
            assertTrue(firstEntered.await(2, TimeUnit.SECONDS));
            assertTrue(publisher.publish(sample("InferenceCompleted")));
            assertTrue(publisher.awaitIdle(Duration.ofSeconds(2)));
            assertTrue(bodies.stream().anyMatch(body -> body.contains("InferenceCompleted")));
        }
        finally {
            release.countDown();
            publisher.close();
            server.stop(0);
        }
    }

    @Test
    void warningsAreRateLimited() throws Exception {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        CountingHandler handler = new CountingHandler();
        Logger logger = Logger.getLogger(MonitoringPublisher.class.getName());
        Level previous = logger.getLevel();
        logger.setLevel(Level.WARNING);
        logger.addHandler(handler);
        MonitoringPublisher publisher = new MonitoringPublisher(MonitoringPublisher.Config.builder()
                .settings(MonitorSettings.resolve("true", "http://127.0.0.1:" + port + "/events", null, null))
                .connectTimeout(Duration.ofMillis(200))
                .requestTimeout(Duration.ofMillis(200))
                .warnInterval(Duration.ofHours(1))
                .build());
        try {
            for (int i = 0; i < 4; i++) {
                assertTrue(publisher.publish(sample("InferenceFailed")));
            }
            assertTrue(publisher.awaitIdle(Duration.ofSeconds(3)));
            assertEquals(1, handler.matching("monitoring publish failed"));
        }
        finally {
            publisher.close();
            logger.removeHandler(handler);
            logger.setLevel(previous);
        }
    }

    private static MonitoringPublisher publisher(HttpServer server, int capacity, Duration timeout, Duration warnInterval) {
        URI endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/events");
        return new MonitoringPublisher(MonitoringPublisher.Config.builder()
                .settings(MonitorSettings.resolve("true", endpoint.toString(), null, null))
                .queueCapacity(capacity)
                .connectTimeout(timeout)
                .requestTimeout(timeout)
                .warnInterval(warnInterval)
                .sessionId("session-1")
                .build());
    }

    private static MonitoringEvent sample(String event) {
        return MonitoringEvent.builder()
                .event(MonitoringEvent.Type.valueOf(event))
                .sessionId("session-1")
                .timestamp("2026-10-15T21:34:00Z")
                .build();
    }

    private static HttpServer server(ExchangeHandler handler) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger index = new AtomicInteger();
        server.createContext("/events", exchange -> {
            try {
                handler.handle(exchange, index.incrementAndGet());
            }
            catch (Exception ex) {
                throw new java.io.IOException(ex);
            }
        });
        ExecutorService executor = Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "monitor-test-http");
            thread.setDaemon(true);
            return thread;
        });
        server.setExecutor(executor);
        server.start();
        return server;
    }

    @FunctionalInterface
    private interface ExchangeHandler {
        void handle(com.sun.net.httpserver.HttpExchange exchange, int index) throws Exception;
    }

    private static final class CountingHandler extends Handler {
        private final List<String> messages = new CopyOnWriteArrayList<>();

        @Override
        public void publish(LogRecord record) {
            if (record.getLevel().intValue() >= Level.WARNING.intValue() && record.getMessage() != null) {
                messages.add(record.getMessage());
            }
        }

        int matching(String fragment) {
            int count = 0;
            for (String message : messages) {
                if (message.contains(fragment)) {
                    count++;
                }
            }
            return count;
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    }
}

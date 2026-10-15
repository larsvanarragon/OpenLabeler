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

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Non-blocking HTTP publisher. Callers only enqueue work. A single daemon thread
 * performs the request with a short timeout. Failures are logged at most once
 * per warning interval and never thrown back to the UI.
 */
public final class MonitoringPublisher implements AutoCloseable {
    public static final String ENABLED = "OPENLABELER_MONITOR_ENABLED";
    public static final String URL = "OPENLABELER_MONITOR_URL";
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(2);
    public static final int DEFAULT_QUEUE_CAPACITY = 256;
    public static final Duration DEFAULT_WARN_INTERVAL = Duration.ofSeconds(30);

    private static final Logger LOG = Logger.getLogger(MonitoringPublisher.class.getName());

    private final Config config;
    private final BlockingQueue<Runnable> queue;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicInteger dropped = new AtomicInteger();
    private final HttpClient client;
    private final Thread worker;
    private long lastWarningNanos;

    public MonitoringPublisher(Config config) {
        this.config = Objects.requireNonNull(config, "config");
        this.queue = new ArrayBlockingQueue<>(Math.max(1, config.queueCapacity));
        if (config.settings.misconfigured()) {
            LOG.warning("OPENLABELER_MONITOR_ENABLED is set but OPENLABELER_MONITOR_URL is missing or invalid; monitoring disabled");
        }
        if (config.settings.enabled()) {
            this.client = HttpClient.newBuilder()
                    .connectTimeout(config.connectTimeout)
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build();
            this.worker = new Thread(this::loop, "OpenLabeler Monitor");
            this.worker.setDaemon(true);
            this.worker.start();
        }
        else {
            this.client = null;
            this.worker = null;
        }
    }

    public static MonitoringPublisher fromEnvironment() {
        return new MonitoringPublisher(Config.fromEnvironment());
    }

    public boolean isEnabled() {
        return config.settings.enabled();
    }

    public String sessionId() {
        return config.sessionId;
    }

    public int droppedCount() {
        return dropped.get();
    }

    /**
     * Enqueues {@code task} and returns immediately. The task runs on the monitor thread.
     * Returns false when monitoring is disabled or the queue is full.
     */
    public boolean execute(Runnable task) {
        if (!isEnabled() || task == null || closed.get()) {
            return false;
        }
        inFlight.incrementAndGet();
        boolean accepted = queue.offer(() -> {
            try {
                task.run();
            }
            catch (Throwable ex) {
                warn("monitoring task failed: " + ex.getClass().getSimpleName());
            }
            finally {
                inFlight.decrementAndGet();
            }
        });
        if (!accepted) {
            inFlight.decrementAndGet();
            dropped.incrementAndGet();
            warn("monitoring queue full; dropping event");
        }
        return accepted;
    }

    /**
     * Enqueues an already built event. Does not block on HTTP.
     */
    public boolean publish(MonitoringEvent event) {
        if (event == null) {
            return false;
        }
        return execute(() -> post(event));
    }

    void post(MonitoringEvent event) {
        if (client == null || config.settings.endpoint() == null) {
            return;
        }
        try {
            String json = MonitoringEvent.toJson(event);
            HttpRequest request = HttpRequest.newBuilder(config.settings.endpoint())
                    .timeout(config.requestTimeout)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
            int status = response.statusCode();
            if (status < 200 || status > 299) {
                warn("monitoring publish failed: HTTP " + status);
            }
        }
        catch (InterruptedException ex) {
            warn("monitoring publish interrupted");
        }
        catch (Exception ex) {
            warn("monitoring publish failed: " + ex.getClass().getSimpleName());
        }
    }

    public boolean awaitIdle(Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (inFlight.get() == 0) {
                return true;
            }
            Thread.sleep(10L);
        }
        return inFlight.get() == 0;
    }

    @Override
    public void close() {
        closed.set(true);
        if (worker != null) {
            worker.interrupt();
        }
    }

    private void loop() {
        while (!closed.get()) {
            try {
                Runnable task = queue.poll(200L, TimeUnit.MILLISECONDS);
                if (task != null) {
                    task.run();
                }
            }
            catch (InterruptedException ex) {
                if (closed.get()) {
                    return;
                }
            }
            catch (Throwable ex) {
                warn("monitoring task failed: " + ex.getClass().getSimpleName());
            }
        }
    }

    private void warn(String message) {
        long now = System.nanoTime();
        synchronized (this) {
            if (now - lastWarningNanos < config.warnInterval.toNanos()) {
                return;
            }
            lastWarningNanos = now;
        }
        LOG.log(Level.WARNING, message);
    }

    public static final class Config {
        private final MonitorSettings settings;
        private final Duration connectTimeout;
        private final Duration requestTimeout;
        private final int queueCapacity;
        private final Duration warnInterval;
        private final String sessionId;

        private Config(MonitorSettings settings, Duration connectTimeout, Duration requestTimeout,
                       int queueCapacity, Duration warnInterval, String sessionId) {
            this.settings = settings == null ? MonitorSettings.disabled() : settings;
            this.connectTimeout = positive(connectTimeout, DEFAULT_TIMEOUT);
            this.requestTimeout = positive(requestTimeout, DEFAULT_TIMEOUT);
            this.queueCapacity = queueCapacity < 1 ? DEFAULT_QUEUE_CAPACITY : queueCapacity;
            this.warnInterval = warnInterval == null || warnInterval.isNegative() ? DEFAULT_WARN_INTERVAL : warnInterval;
            this.sessionId = sessionId == null || sessionId.isBlank() ? UUID.randomUUID().toString() : sessionId.trim();
        }

        public static Config fromEnvironment() {
            return builder().settings(MonitorSettings.fromEnvironment()).build();
        }

        public static Builder builder() {
            return new Builder();
        }

        private static Duration positive(Duration value, Duration fallback) {
            if (value == null || value.isZero() || value.isNegative()) {
                return fallback;
            }
            return value;
        }

        public static final class Builder {
            private MonitorSettings settings = MonitorSettings.disabled();
            private Duration connectTimeout = DEFAULT_TIMEOUT;
            private Duration requestTimeout = DEFAULT_TIMEOUT;
            private int queueCapacity = DEFAULT_QUEUE_CAPACITY;
            private Duration warnInterval = DEFAULT_WARN_INTERVAL;
            private String sessionId = UUID.randomUUID().toString();

            public Builder settings(MonitorSettings settings) {
                this.settings = settings;
                return this;
            }

            public Builder connectTimeout(Duration connectTimeout) {
                this.connectTimeout = connectTimeout;
                return this;
            }

            public Builder requestTimeout(Duration requestTimeout) {
                this.requestTimeout = requestTimeout;
                return this;
            }

            public Builder queueCapacity(int queueCapacity) {
                this.queueCapacity = queueCapacity;
                return this;
            }

            public Builder warnInterval(Duration warnInterval) {
                this.warnInterval = warnInterval;
                return this;
            }

            public Builder sessionId(String sessionId) {
                this.sessionId = sessionId;
                return this;
            }

            public Config build() {
                return new Config(settings, connectTimeout, requestTimeout, queueCapacity, warnInterval, sessionId);
            }
        }
    }
}

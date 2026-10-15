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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenLabelerMonitoringTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void signalsPublishDigestsAndCorrelatedOperations() throws Exception {
        Path dir = Files.createTempDirectory("secret-dir-MARKER");
        Path image = dir.resolve("cat.jpg");
        Files.writeString(image, "secret-bytes-MARKER");
        String modelPath = dir.resolve("saved_model").toString();
        List<String> bodies = new CopyOnWriteArrayList<>();
        List<String> methods = new CopyOnWriteArrayList<>();
        List<String> contentTypes = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/events", exchange -> {
            methods.add(exchange.getRequestMethod());
            contentTypes.add(exchange.getRequestHeaders().getFirst("Content-Type"));
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        ExecutorService executor = Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "monitor-signal-http");
            thread.setDaemon(true);
            return thread;
        });
        server.setExecutor(executor);
        server.start();

        URI endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/events");
        MonitoringPublisher publisher = new MonitoringPublisher(MonitoringPublisher.Config.builder()
                .settings(MonitorSettings.resolve("true", endpoint.toString(), null, null))
                .sessionId("session-42")
                .requestTimeout(Duration.ofSeconds(2))
                .connectTimeout(Duration.ofSeconds(2))
                .build());
        OpenLabelerMonitoring monitoring = new OpenLabelerMonitoring(publisher);
        try {
            long started = System.nanoTime();
            monitoring.imageLoaded(image.toFile(), 3);
            long elapsedMs = Duration.ofNanos(System.nanoTime() - started).toMillis();
            assertTrue(elapsedMs < 500, "imageLoaded blocked for " + elapsedMs + "ms");
            monitoring.annotationSaved(image.toFile(), 3);
            monitoring.annotationSaveFailed(image.toFile(), new IOException("denied " + image));
            monitoring.inferenceStarted(image.toFile(), "operation-7");
            monitoring.inferenceCompleted(image.toFile(), "operation-7", 1,
                    List.of(new MonitoringEvent.Prediction("nebula", 0.75)), 18);
            monitoring.inferenceFailed(image.toFile(), "operation-7", new IOException("detect " + modelPath), 4);
            monitoring.hintAccepted(image.toFile(), "operation-7", "nebula", 0.75);
            monitoring.modelLoaded(modelPath);
            assertTrue(publisher.awaitIdle(Duration.ofSeconds(3)));

            assertEquals(8, bodies.size());
            assertTrue(methods.stream().allMatch("POST"::equals));
            assertTrue(contentTypes.stream().allMatch("application/json"::equals));
            String joined = String.join("\n", bodies);
            assertFalse(joined.contains("secret-dir-MARKER"));
            assertFalse(joined.contains("secret-bytes-MARKER"));
            assertFalse(joined.contains(image.toAbsolutePath().toString()));

            JsonNode loaded = find(bodies, "ImageLoaded");
            assertEquals("session-42", loaded.get("session_id").asText());
            assertEquals("cat.jpg", loaded.get("image_name").asText());
            assertEquals(MonitoringIdentity.sha256File(image), loaded.get("image_sha256").asText());
            assertEquals(3, loaded.get("object_count").asInt());
            assertTrue(loaded.get("timestamp").asText().matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?Z"));

            JsonNode completed = find(bodies, "InferenceCompleted");
            assertEquals("operation-7", completed.get("operation_id").asText());
            assertEquals(1, completed.get("hint_count").asInt());
            assertEquals(18, completed.get("duration_ms").asInt());
            assertEquals("nebula", completed.get("predictions").get(0).get("label").asText());
            assertEquals(0.75, completed.get("predictions").get(0).get("score").asDouble());

            JsonNode failed = find(bodies, "InferenceFailed");
            assertEquals("operation-7", failed.get("operation_id").asText());
            assertFalse(failed.get("error").asText().contains("secret-dir-MARKER"));

            JsonNode hint = find(bodies, "HintAccepted");
            assertEquals("operation-7", hint.get("operation_id").asText());
            assertEquals("nebula", hint.get("label").asText());

            JsonNode model = find(bodies, "ModelLoaded");
            assertEquals(MonitoringIdentity.modelId(modelPath), model.get("model_id").asText());
            assertFalse(model.has("image_name"));
        }
        finally {
            publisher.close();
            server.stop(0);
            executor.shutdownNow();
            Files.deleteIfExists(image);
            Files.deleteIfExists(dir);
        }
    }

    private JsonNode find(List<String> bodies, String event) throws IOException {
        for (String body : bodies) {
            JsonNode node = mapper.readTree(body);
            if (event.equals(node.get("event").asText())) {
                return node;
            }
        }
        throw new AssertionError("missing event " + event + " in " + bodies);
    }
}

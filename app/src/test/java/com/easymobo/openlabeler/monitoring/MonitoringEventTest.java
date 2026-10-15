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
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MonitoringEventTest {
    private static final Set<String> EVENT_KEYS = Set.of(
            "schema_version", "event", "session_id", "operation_id", "timestamp",
            "image_name", "image_sha256", "model_id", "object_count", "hint_count",
            "duration_ms", "label", "score", "predictions", "error");

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void jsonUsesSchemaNamesAndOmitsEmptyFields() throws Exception {
        MonitoringEvent event = MonitoringEvent.builder()
                .event(MonitoringEvent.Type.ImageLoaded)
                .sessionId("session-1")
                .timestamp("2026-10-15T21:34:00.123Z")
                .imageName("cat.jpg")
                .imageSha256("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
                .objectCount(2)
                .build();

        JsonNode json = mapper.readTree(MonitoringEvent.toJson(event));
        assertEquals(1, json.get("schema_version").asInt());
        assertEquals("ImageLoaded", json.get("event").asText());
        assertEquals("session-1", json.get("session_id").asText());
        assertEquals("cat.jpg", json.get("image_name").asText());
        assertEquals(2, json.get("object_count").asInt());
        assertFalse(json.has("operation_id"));
        assertFalse(json.has("predictions"));
        assertFalse(json.has("error"));
        assertSchemaKeys(json);
    }

    @Test
    void dropsInvalidDigestAndRedactsPaths() throws Exception {
        MonitoringEvent event = MonitoringEvent.builder()
                .event(MonitoringEvent.Type.AnnotationSaveFailed)
                .sessionId("session-1")
                .timestamp("2026-10-15T21:34:00Z")
                .imageName("/home/secret-dir-MARKER/cat.jpg")
                .imageSha256("not-a-digest")
                .modelId("/opt/secret-dir-MARKER/saved_model")
                .error("failed /home/secret-dir-MARKER/cat.jpg")
                .score(1.4)
                .build();

        String json = MonitoringEvent.toJson(event);
        JsonNode node = mapper.readTree(json);
        assertEquals("cat.jpg", node.get("image_name").asText());
        assertFalse(node.has("image_sha256"));
        assertFalse(json.contains("secret-dir-MARKER"));
        assertFalse(json.contains("/home"));
        assertFalse(json.contains("/opt"));
        assertEquals(1.0, node.get("score").asDouble());
        assertTrue(node.get("model_id").asText().matches("[a-f0-9]{64}"));
        assertTrue(node.get("error").asText().contains("[path]"));
        assertSchemaKeys(node);
    }

    @Test
    void predictionsAreImmutableAndCapped() {
        List<MonitoringEvent.Prediction> source = new ArrayList<>();
        for (int i = 0; i < 1001; i++) {
            source.add(new MonitoringEvent.Prediction("label-" + i, 2.0));
        }
        MonitoringEvent event = MonitoringEvent.builder()
                .event(MonitoringEvent.Type.InferenceCompleted)
                .sessionId("session-1")
                .timestamp("2026-10-15T21:34:00Z")
                .predictions(source)
                .build();

        source.clear();
        assertEquals(1000, event.predictions().size());
        assertEquals(1.0, event.predictions().get(0).score());
        assertThrows(UnsupportedOperationException.class, () -> event.predictions().add(
                new MonitoringEvent.Prediction("extra", 0.1)));
    }

    @Test
    void completedInferenceKeepsAnEmptyPredictionArray() throws Exception {
        MonitoringEvent event = MonitoringEvent.builder()
                .event(MonitoringEvent.Type.InferenceCompleted)
                .sessionId("session-1")
                .timestamp("2026-10-15T21:34:00Z")
                .predictions(List.of())
                .build();

        JsonNode json = mapper.readTree(MonitoringEvent.toJson(event));
        assertTrue(json.has("predictions"));
        assertTrue(json.get("predictions").isArray());
        assertEquals(0, json.get("predictions").size());
    }

    @Test
    void requiredFieldsAreEnforced() {
        assertThrows(IllegalArgumentException.class, () -> MonitoringEvent.builder()
                .event(MonitoringEvent.Type.ModelLoaded)
                .timestamp("2026-10-15T21:34:00Z")
                .build());
        assertNull(MonitoringEvent.builder()
                .event(MonitoringEvent.Type.HintAccepted)
                .sessionId("session-1")
                .timestamp("2026-10-15T21:34:00Z")
                .label("   ")
                .score(Double.NaN)
                .build()
                .label());
    }

    private static void assertSchemaKeys(JsonNode json) {
        Set<String> names = new HashSet<>();
        json.fieldNames().forEachRemaining(names::add);
        assertTrue(EVENT_KEYS.containsAll(names));
        JsonNode predictions = json.get("predictions");
        if (predictions != null) {
            predictions.forEach(prediction -> {
                Set<String> predictionKeys = new HashSet<>();
                prediction.fieldNames().forEachRemaining(predictionKeys::add);
                assertEquals(Set.of("label", "score"), predictionKeys);
            });
        }
    }
}

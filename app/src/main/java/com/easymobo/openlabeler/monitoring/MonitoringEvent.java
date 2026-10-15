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

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Immutable monitoring event. JSON field names match
 * {@code models/openlabeler/events.schema.json}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({
        "schema_version",
        "event",
        "session_id",
        "operation_id",
        "timestamp",
        "image_name",
        "image_sha256",
        "model_id",
        "object_count",
        "hint_count",
        "duration_ms",
        "label",
        "score",
        "predictions",
        "error"
})
public record MonitoringEvent(
        @JsonProperty("schema_version") int schemaVersion,
        @JsonProperty("event") Type event,
        @JsonProperty("session_id") String sessionId,
        @JsonProperty("operation_id") String operationId,
        @JsonProperty("timestamp") String timestamp,
        @JsonProperty("image_name") String imageName,
        @JsonProperty("image_sha256") String imageSha256,
        @JsonProperty("model_id") String modelId,
        @JsonProperty("object_count") Integer objectCount,
        @JsonProperty("hint_count") Integer hintCount,
        @JsonProperty("duration_ms") Long durationMs,
        @JsonProperty("label") String label,
        @JsonProperty("score") Double score,
        @JsonProperty("predictions") List<Prediction> predictions,
        @JsonProperty("error") String error
) {
    public static final int SCHEMA_VERSION = 1;

    private static final Pattern SHA256 = Pattern.compile("^[a-f0-9]{64}$");
    private static final ObjectMapper JSON = new ObjectMapper();

    public MonitoringEvent {
        if (schemaVersion != SCHEMA_VERSION) {
            throw new IllegalArgumentException("schema_version");
        }
        Objects.requireNonNull(event, "event");
        sessionId = required(sessionId, 200);
        operationId = optional(operationId, 200);
        timestamp = required(timestamp, 40);
        imageName = fileNameOnly(imageName);
        if (imageSha256 != null && !SHA256.matcher(imageSha256).matches()) {
            imageSha256 = null;
        }
        if (modelId != null && (modelId.indexOf('/') >= 0 || modelId.indexOf('\\') >= 0)) {
            modelId = MonitoringIdentity.sha256Utf8(modelId.trim());
        }
        modelId = optional(modelId, 255);
        if (objectCount != null && objectCount < 0) {
            objectCount = null;
        }
        if (hintCount != null && hintCount < 0) {
            hintCount = null;
        }
        if (durationMs != null && durationMs < 0) {
            durationMs = null;
        }
        label = optional(label, 255);
        if (score != null) {
            if (score.isNaN()) {
                score = null;
            }
            else {
                score = Math.max(0d, Math.min(1d, score));
            }
        }
        predictions = sanitize(predictions);
        error = optional(error == null ? null : MonitoringIdentity.redactPaths(error), 255);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static String toJson(MonitoringEvent event) {
        try {
            return JSON.writeValueAsString(event);
        }
        catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("Unable to encode monitoring event", ex);
        }
    }

    private static List<Prediction> sanitize(List<Prediction> predictions) {
        if (predictions == null) {
            return null;
        }
        if (predictions.isEmpty()) {
            return List.of();
        }
        List<Prediction> cleaned = new ArrayList<>();
        for (Prediction prediction : predictions) {
            if (prediction == null || prediction.label() == null) {
                continue;
            }
            cleaned.add(prediction);
            if (cleaned.size() == 1000) {
                break;
            }
        }
        return cleaned.isEmpty() ? null : List.copyOf(cleaned);
    }

    private static String required(String value, int max) {
        String text = optional(value, max);
        if (text == null) {
            throw new IllegalArgumentException("missing required text");
        }
        return text;
    }

    private static String fileNameOnly(String value) {
        String text = optional(value, 4096);
        if (text == null) {
            return null;
        }
        int slash = Math.max(text.lastIndexOf('/'), text.lastIndexOf('\\'));
        if (slash >= 0) {
            text = text.substring(slash + 1);
        }
        return optional(text, 255);
    }

    private static String optional(String value, int max) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }

    public enum Type {
        ImageLoaded,
        AnnotationSaved,
        AnnotationSaveFailed,
        InferenceStarted,
        InferenceCompleted,
        InferenceFailed,
        HintAccepted,
        ModelLoaded
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Prediction(
            @JsonProperty("label") String label,
            @JsonProperty("score") double score
    ) {
        public Prediction {
            label = optional(label, 255);
            if (Double.isNaN(score) || score < 0d) {
                score = 0d;
            }
            else if (score > 1d) {
                score = 1d;
            }
        }
    }

    public static final class Builder {
        private Type event;
        private String sessionId;
        private String operationId;
        private String timestamp;
        private String imageName;
        private String imageSha256;
        private String modelId;
        private Integer objectCount;
        private Integer hintCount;
        private Long durationMs;
        private String label;
        private Double score;
        private List<Prediction> predictions;
        private String error;

        public Builder event(Type event) {
            this.event = event;
            return this;
        }

        public Builder sessionId(String sessionId) {
            this.sessionId = sessionId;
            return this;
        }

        public Builder operationId(String operationId) {
            this.operationId = operationId;
            return this;
        }

        public Builder timestamp(String timestamp) {
            this.timestamp = timestamp;
            return this;
        }

        public Builder imageName(String imageName) {
            this.imageName = imageName;
            return this;
        }

        public Builder imageSha256(String imageSha256) {
            this.imageSha256 = imageSha256;
            return this;
        }

        public Builder modelId(String modelId) {
            this.modelId = modelId;
            return this;
        }

        public Builder objectCount(int objectCount) {
            this.objectCount = objectCount;
            return this;
        }

        public Builder hintCount(int hintCount) {
            this.hintCount = hintCount;
            return this;
        }

        public Builder durationMs(long durationMs) {
            this.durationMs = durationMs;
            return this;
        }

        public Builder label(String label) {
            this.label = label;
            return this;
        }

        public Builder score(double score) {
            this.score = score;
            return this;
        }

        public Builder predictions(List<Prediction> predictions) {
            this.predictions = predictions == null ? null : List.copyOf(predictions);
            return this;
        }

        public Builder error(String error) {
            this.error = error;
            return this;
        }

        public MonitoringEvent build() {
            return new MonitoringEvent(
                    SCHEMA_VERSION,
                    event,
                    sessionId,
                    operationId,
                    timestamp,
                    imageName,
                    imageSha256,
                    modelId,
                    objectCount,
                    hintCount,
                    durationMs,
                    label,
                    score,
                    predictions,
                    error);
        }
    }
}

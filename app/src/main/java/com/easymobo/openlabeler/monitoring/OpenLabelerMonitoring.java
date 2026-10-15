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

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Process-wide monitoring signals. Calls return immediately; hashing and HTTP
 * happen on the publisher thread.
 */
public final class OpenLabelerMonitoring {
    public static final String HINT_OPERATION_ID = "openlabeler.monitoring.operationId";

    private static final Logger LOG = Logger.getLogger(OpenLabelerMonitoring.class.getName());

    private static final class Holder {
        private static final OpenLabelerMonitoring INSTANCE = fromEnvironment();
    }

    private final MonitoringPublisher publisher;
    private final String sessionId;
    private Path cachedPath;
    private long cachedStamp;
    private String cachedHash;

    public OpenLabelerMonitoring(MonitoringPublisher publisher) {
        this.publisher = publisher;
        this.sessionId = publisher.sessionId();
    }

    public static OpenLabelerMonitoring get() {
        return Holder.INSTANCE;
    }

    public static OpenLabelerMonitoring fromEnvironment() {
        return new OpenLabelerMonitoring(MonitoringPublisher.fromEnvironment());
    }

    public MonitoringPublisher publisher() {
        return publisher;
    }

    public void imageLoaded(File image, int objectCount) {
        safely(() -> {
            int count = Math.max(0, objectCount);
            track(MonitoringEvent.Type.ImageLoaded, image, builder -> builder.objectCount(count));
        });
    }

    public void annotationSaved(File image, int objectCount) {
        safely(() -> {
            int count = Math.max(0, objectCount);
            track(MonitoringEvent.Type.AnnotationSaved, image, builder -> builder.objectCount(count));
        });
    }

    public void annotationSaveFailed(File image, Throwable failure) {
        safely(() -> track(MonitoringEvent.Type.AnnotationSaveFailed, image,
                builder -> builder.error(MonitoringIdentity.errorText(failure))));
    }

    public void inferenceStarted(File image, String operationId) {
        safely(() -> track(MonitoringEvent.Type.InferenceStarted, image,
                builder -> builder.operationId(operationId)));
    }

    public void inferenceCompleted(File image, String operationId, int hintCount,
                                   List<MonitoringEvent.Prediction> predictions, long durationMs) {
        safely(() -> {
            int hints = Math.max(0, hintCount);
            long duration = Math.max(0L, durationMs);
            List<MonitoringEvent.Prediction> copy = predictions == null ? List.of() : List.copyOf(predictions);
            track(MonitoringEvent.Type.InferenceCompleted, image, builder -> builder
                    .operationId(operationId)
                    .hintCount(hints)
                    .durationMs(duration)
                    .predictions(copy));
        });
    }

    public void inferenceFailed(File image, String operationId, Throwable failure, long durationMs) {
        safely(() -> {
            long duration = Math.max(0L, durationMs);
            track(MonitoringEvent.Type.InferenceFailed, image, builder -> builder
                    .operationId(operationId)
                    .durationMs(duration)
                    .error(MonitoringIdentity.errorText(failure)));
        });
    }

    public void hintAccepted(File image, String operationId, String label, double score) {
        safely(() -> track(MonitoringEvent.Type.HintAccepted, image, builder -> builder
                .operationId(operationId)
                .label(label)
                .score(score)));
    }

    public void modelLoaded(String modelPath) {
        safely(() -> {
            if (!publisher.isEnabled()) {
                return;
            }
            String timestamp = MonitoringIdentity.timestamp(Instant.now());
            publisher.execute(() -> {
                MonitoringEvent.Builder builder = base(MonitoringEvent.Type.ModelLoaded, timestamp);
                String modelId = MonitoringIdentity.modelId(modelPath);
                if (modelId != null) {
                    builder.modelId(modelId);
                }
                publisher.post(builder.build());
            });
        });
    }

    private void track(MonitoringEvent.Type type, File image, Consumer<MonitoringEvent.Builder> customizer) {
        if (!publisher.isEnabled()) {
            return;
        }
        String imageName = image == null ? null : MonitoringIdentity.imageName(image.toPath());
        Path path = image == null ? null : image.toPath();
        String timestamp = MonitoringIdentity.timestamp(Instant.now());
        publisher.execute(() -> {
            MonitoringEvent.Builder builder = base(type, timestamp).imageName(imageName);
            if (path != null) {
                String hash = hashImage(path);
                if (hash != null) {
                    builder.imageSha256(hash);
                }
            }
            customizer.accept(builder);
            publisher.post(builder.build());
        });
    }

    private MonitoringEvent.Builder base(MonitoringEvent.Type type, String timestamp) {
        return MonitoringEvent.builder()
                .event(type)
                .sessionId(sessionId)
                .timestamp(timestamp);
    }

    private String hashImage(Path path) {
        try {
            BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class);
            long stamp = attributes.size() ^ attributes.lastModifiedTime().toMillis();
            if (path.equals(cachedPath) && stamp == cachedStamp && cachedHash != null) {
                return cachedHash;
            }
            String hash = MonitoringIdentity.sha256File(path);
            cachedPath = path;
            cachedStamp = stamp;
            cachedHash = hash;
            return hash;
        }
        catch (Exception ex) {
            return null;
        }
    }

    private static void safely(Runnable action) {
        try {
            action.run();
        }
        catch (Throwable ex) {
            LOG.log(Level.FINE, "monitoring signal dropped", ex);
        }
    }
}

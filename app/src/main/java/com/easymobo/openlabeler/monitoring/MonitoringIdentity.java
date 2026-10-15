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

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.regex.Pattern;

/**
 * Identifiers safe to publish: file name, SHA-256 digests, and redacted errors.
 * Full paths and file bytes stay on the machine.
 */
public final class MonitoringIdentity {
    private static final Pattern ABSOLUTE_UNIX_PATH = Pattern.compile("(?:/[^\\s/]+){2,}");
    private static final Pattern WINDOWS_PATH = Pattern.compile("[A-Za-z]:[\\\\/][^\\s]+");
    private static final Pattern FILE_URL = Pattern.compile("(?i)file:[^\\s]+");

    private MonitoringIdentity() {
    }

    public static String timestamp(Instant instant) {
        return DateTimeFormatter.ISO_INSTANT.format(instant.truncatedTo(ChronoUnit.MILLIS));
    }

    public static String imageName(Path path) {
        if (path == null) {
            return null;
        }
        Path name = path.getFileName();
        if (name == null) {
            return null;
        }
        String text = name.toString().trim();
        if (text.isEmpty() || ".".equals(text) || "..".equals(text)) {
            return null;
        }
        return text.length() <= 255 ? text : text.substring(0, 255);
    }

    /**
     * SHA-256 of file contents. Returns null when the file cannot be read.
     * The digest is lowercase hex; the bytes themselves are not retained.
     */
    public static String sha256File(Path path) {
        if (path == null) {
            return null;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(path)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (read > 0) {
                        digest.update(buffer, 0, read);
                    }
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        }
        catch (IOException | NoSuchAlgorithmException ex) {
            return null;
        }
    }

    public static String sha256Utf8(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        }
        catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    /**
     * Stable model identity derived from the model location. The path is not returned.
     */
    public static String modelId(String modelPath) {
        if (modelPath == null || modelPath.isBlank()) {
            return null;
        }
        String identity;
        try {
            identity = Path.of(modelPath).toAbsolutePath().normalize().toString();
        }
        catch (InvalidPathException ex) {
            identity = modelPath.trim();
        }
        return sha256Utf8(identity);
    }

    public static String errorText(Throwable failure) {
        if (failure == null) {
            return null;
        }
        String detail = failure.getMessage();
        String text = failure.getClass().getSimpleName();
        if (detail != null && !detail.isBlank()) {
            text = text + ": " + redactPaths(detail);
        }
        text = text.replaceAll("\\s+", " ").trim();
        return text.length() <= 255 ? text : text.substring(0, 255);
    }

    static String redactPaths(String message) {
        String redacted = FILE_URL.matcher(message).replaceAll("[path]");
        redacted = WINDOWS_PATH.matcher(redacted).replaceAll("[path]");
        return ABSOLUTE_UNIX_PATH.matcher(redacted).replaceAll("[path]");
    }
}

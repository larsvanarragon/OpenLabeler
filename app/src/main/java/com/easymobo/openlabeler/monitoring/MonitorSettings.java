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
import java.util.Locale;

/**
 * Resolves {@code OPENLABELER_MONITOR_ENABLED} and {@code OPENLABELER_MONITOR_URL}.
 * A system property wins over the environment variable of the same name.
 * Monitoring stays off unless explicitly enabled with an http(s) URL.
 */
public final class MonitorSettings {
    private final boolean enabled;
    private final URI endpoint;
    private final boolean misconfigured;

    private MonitorSettings(boolean enabled, URI endpoint, boolean misconfigured) {
        this.enabled = enabled;
        this.endpoint = endpoint;
        this.misconfigured = misconfigured;
    }

    public static MonitorSettings disabled() {
        return new MonitorSettings(false, null, false);
    }

    public static MonitorSettings fromEnvironment() {
        return resolve(
                System.getProperty(MonitoringPublisher.ENABLED),
                System.getProperty(MonitoringPublisher.URL),
                System.getenv(MonitoringPublisher.ENABLED),
                System.getenv(MonitoringPublisher.URL));
    }

    public static MonitorSettings resolve(String propertyEnabled, String propertyUrl, String envEnabled, String envUrl) {
        String enabledRaw = first(propertyEnabled, envEnabled);
        String urlRaw = first(propertyUrl, envUrl);
        if (!isEnabledFlag(enabledRaw)) {
            return disabled();
        }
        URI endpoint = endpoint(urlRaw);
        if (endpoint == null) {
            return new MonitorSettings(false, null, true);
        }
        return new MonitorSettings(true, endpoint, false);
    }

    public boolean enabled() {
        return enabled;
    }

    public URI endpoint() {
        return endpoint;
    }

    public boolean misconfigured() {
        return misconfigured;
    }

    static boolean isEnabledFlag(String raw) {
        if (raw == null || raw.isBlank()) {
            return false;
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "1", "true", "yes", "on" -> true;
            default -> false;
        };
    }

    private static URI endpoint(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            URI uri = URI.create(raw.trim());
            String scheme = uri.getScheme();
            if (scheme == null || uri.getHost() == null) {
                return null;
            }
            if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
                return null;
            }
            return uri;
        }
        catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static String first(String preferred, String fallback) {
        if (preferred != null && !preferred.isBlank()) {
            return preferred.trim();
        }
        if (fallback != null && !fallback.isBlank()) {
            return fallback.trim();
        }
        return null;
    }
}

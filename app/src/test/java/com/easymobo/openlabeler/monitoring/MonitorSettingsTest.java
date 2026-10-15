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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MonitorSettingsTest {
    @Test
    void disabledUnlessExplicitlyEnabledWithHttpUrl() {
        assertFalse(MonitorSettings.resolve(null, null, null, null).enabled());
        assertFalse(MonitorSettings.resolve("false", "http://127.0.0.1/events", null, null).enabled());
        assertFalse(MonitorSettings.resolve("0", null, "true", "http://127.0.0.1/events").enabled());
        assertFalse(MonitorSettings.resolve("", "  ", null, null).enabled());

        MonitorSettings missingUrl = MonitorSettings.resolve("true", "   ", null, null);
        assertFalse(missingUrl.enabled());
        assertTrue(missingUrl.misconfigured());
        assertNull(missingUrl.endpoint());

        MonitorSettings fileUrl = MonitorSettings.resolve("yes", "file:///tmp/events", null, null);
        assertFalse(fileUrl.enabled());
        assertTrue(fileUrl.misconfigured());
    }

    @Test
    void systemPropertyOverridesEnvironment() {
        MonitorSettings settings = MonitorSettings.resolve(
                "on", "http://127.0.0.1/from-prop",
                "false", "https://example.test/from-env");
        assertTrue(settings.enabled());
        assertEquals("http", settings.endpoint().getScheme());
        assertEquals("/from-prop", settings.endpoint().getPath());
        assertFalse(settings.misconfigured());
    }

    @Test
    void environmentIsUsedWhenPropertiesAreBlank() {
        MonitorSettings settings = MonitorSettings.resolve(" ", null, "1", "https://monitor.example/events");
        assertTrue(settings.enabled());
        assertEquals("https", settings.endpoint().getScheme());
        assertEquals("monitor.example", settings.endpoint().getHost());
    }
}

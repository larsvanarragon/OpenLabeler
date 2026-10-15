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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MonitoringIdentityTest {
    @Test
    void hashesFileContentsWithoutKeepingThem() throws IOException {
        Path dir = Files.createTempDirectory("secret-dir-MARKER");
        Path image = dir.resolve("cat.jpg");
        Files.writeString(image, "abc");
        try {
            assertEquals("cat.jpg", MonitoringIdentity.imageName(image));
            assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                    MonitoringIdentity.sha256File(image));
            assertFalse(MonitoringIdentity.imageName(image).contains("secret-dir-MARKER"));
        }
        finally {
            Files.deleteIfExists(image);
            Files.deleteIfExists(dir);
        }
    }

    @Test
    void modelIdIsDigestOfLocation() {
        String path = "/opt/secret-dir-MARKER/saved_model";
        String modelId = MonitoringIdentity.modelId(path);
        assertTrue(modelId.matches("[a-f0-9]{64}"));
        assertFalse(modelId.contains("secret-dir-MARKER"));
        assertEquals(modelId, MonitoringIdentity.modelId(path));
    }

    @Test
    void missingFileYieldsNoDigest() {
        assertNull(MonitoringIdentity.sha256File(Path.of("missing-secret-dir-MARKER.jpg")));
    }

    @Test
    void errorTextDropsPathsAndStaysBounded() {
        StringBuilder longMessage = new StringBuilder("failed /home/secret-dir-MARKER/cat.jpg ");
        longMessage.append("x".repeat(400));
        String text = MonitoringIdentity.errorText(new IOException(longMessage.toString()));
        assertTrue(text.startsWith("IOException:"));
        assertTrue(text.contains("[path]"));
        assertFalse(text.contains("secret-dir-MARKER"));
        assertTrue(text.length() <= 255);
    }
}

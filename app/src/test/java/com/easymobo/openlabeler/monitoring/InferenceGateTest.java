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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InferenceGateTest {
    @Test
    void suppressesDuplicateWhileImageIsInFlight() {
        InferenceGate gate = new InferenceGate();
        assertFalse(gate.tryAcquire(null));
        assertTrue(gate.tryAcquire("/images/a.jpg"));
        assertFalse(gate.tryAcquire("/images/a.jpg"));
        assertTrue(gate.tryAcquire("/images/b.jpg"));
        gate.release("/images/a.jpg");
        assertTrue(gate.tryAcquire("/images/a.jpg"));
        gate.release("/images/missing.jpg");
        assertFalse(gate.tryAcquire("/images/b.jpg"));
    }
}

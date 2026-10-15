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

import java.util.HashSet;
import java.util.Set;

/**
 * Suppresses a second inference for an image while one is already running.
 * Other images may run at the same time.
 */
public final class InferenceGate {
    private final Set<Object> inFlight = new HashSet<>();

    public synchronized boolean tryAcquire(Object key) {
        if (key == null) {
            return false;
        }
        return inFlight.add(key);
    }

    public synchronized void release(Object key) {
        if (key != null) {
            inFlight.remove(key);
        }
    }
}

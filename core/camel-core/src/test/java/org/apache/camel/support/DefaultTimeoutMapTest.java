/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.camel.support;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.apache.camel.TimeoutMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfSystemProperty;
import org.junit.jupiter.api.parallel.Isolated;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

@Isolated("Depends on precise timing that may be hard to achieve if the system is under pressure")
@DisabledIfSystemProperty(named = "ci.env.name", matches = ".*", disabledReason = "Flaky on Github CI")
public class DefaultTimeoutMapTest {

    private static final Logger LOG = LoggerFactory.getLogger(DefaultTimeoutMapTest.class);
    private final ScheduledExecutorService executor = new ScheduledThreadPoolExecutor(1);

    @Test
    public void testDefaultTimeoutMap() {
        DefaultTimeoutMap<?, ?> map = new DefaultTimeoutMap<>(executor);
        map.start();
        assertTrue(map.currentTime() > 0);

        assertEquals(0, map.size());

        map.stop();
    }

    @Test
    public void testDefaultTimeoutMapPurge() {
        DefaultTimeoutMap<String, Integer> map = new DefaultTimeoutMap<>(executor, 100);
        map.start();
        assertTrue(map.currentTime() > 0);

        assertEquals(0, map.size());

        map.put("A", 123, 50);
        assertEquals(1, map.size());

        await().atMost(Duration.ofSeconds(2))
                .untilAsserted(() -> assertEquals(0, map.size()));

        map.stop();
    }

    @Test
    public void testDefaultTimeoutMapForcePurge() throws Exception {
        DefaultTimeoutMap<String, Integer> map = new DefaultTimeoutMap<>(executor, 100);
        // map.start(); // Do not start background purge
        assertTrue(map.currentTime() > 0);

        assertEquals(0, map.size());

        map.put("A", 123, 10);
        assertEquals(1, map.size());

        Thread.sleep(50);

        // will purge and remove old entries
        map.purge();

        assertEquals(0, map.size());
    }

    @Test
    public void testDefaultTimeoutMapGetRemove() {
        DefaultTimeoutMap<String, Integer> map = new DefaultTimeoutMap<>(executor, 100);
        map.start();
        assertTrue(map.currentTime() > 0);

        assertEquals(0, map.size());

        map.put("A", 123, 50);
        assertEquals(1, map.size());

        assertEquals(123, (int) map.get("A"));

        Object old = map.remove("A");
        assertEquals(123, old);
        assertNull(map.get("A"));
        assertEquals(0, map.size());

        map.stop();
    }

    @Test
    public void testExecutor() {
        ScheduledExecutorService e = Executors.newScheduledThreadPool(2);

        DefaultTimeoutMap<String, Integer> map = new DefaultTimeoutMap<>(e, 50);
        map.start();
        assertEquals(50, map.getPurgePollTime());

        map.put("A", 123, 100);
        assertEquals(1, map.size());

        // should have been timed out now
        await().atMost(Duration.ofSeconds(2))
                .untilAsserted(() -> assertEquals(0, map.size()));

        assertSame(e, map.getExecutor());

        map.stop();
    }

    @Test
    public void testExpiredInCorrectOrder() {
        final List<String> keys = new ArrayList<>();
        final List<Integer> values = new ArrayList<>();

        DefaultTimeoutMap<String, Integer> map = new DefaultTimeoutMap<>(executor, 100);
        map.addListener((type, key, value) -> {
            if (type == TimeoutMap.Listener.Type.Evict) {
                keys.add(key);
                values.add(value);
            }
        });
        map.start();
        assertEquals(0, map.size());

        map.put("A", 1, 50);
        map.put("B", 2, 30);
        map.put("C", 3, 40);
        map.put("D", 4, 20);
        map.put("E", 5, 40);
        // is not expired
        map.put("F", 6, 800);

        await().atMost(Duration.ofSeconds(2))
                .untilAsserted(() -> {
                    assertFalse(keys.isEmpty());
                    assertEquals("D", keys.get(0));
                });

        assertEquals(4, values.get(0).intValue());
        assertEquals("B", keys.get(1));
        assertEquals(2, values.get(1).intValue());
        assertEquals("C", keys.get(2));
        assertEquals(3, values.get(2).intValue());
        assertEquals("E", keys.get(3));
        assertEquals(5, values.get(3).intValue());
        assertEquals("A", keys.get(4));
        assertEquals(1, values.get(4).intValue());

        assertEquals(1, map.size());

        map.stop();
    }

    @Test
    public void testDefaultTimeoutMapStopStart() {
        DefaultTimeoutMap<String, Integer> map = new DefaultTimeoutMap<>(executor, 100);
        map.start();
        map.put("A", 1, 500);

        assertEquals(1, map.size());
        map.stop();

        assertEquals(0, map.size());
        map.put("A", 1, 50);

        // should not timeout as the scheduler doesn't run
        await().atMost(Duration.ofSeconds(1))
                .untilAsserted(() -> assertEquals(1, map.size()));

        // start
        map.start();

        // start and wait for scheduler to purge
        await().atMost(2, TimeUnit.SECONDS).untilAsserted(() -> assertEquals(0, map.size()));

        map.stop();
    }

}

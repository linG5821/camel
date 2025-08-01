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
package org.apache.camel.impl;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.apache.camel.CamelContext;
import org.apache.camel.ContextTestSupport;
import org.apache.camel.spi.ExecutorServiceManager;
import org.apache.camel.spi.ThreadPoolProfile;
import org.apache.camel.util.concurrent.SizedScheduledExecutorService;
import org.apache.camel.util.concurrent.ThreadPoolRejectedPolicy;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfSystemProperty;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisabledIfSystemProperty(named = "camel.threads.virtual.enabled", matches = "true",
                          disabledReason = "In case of Virtual Threads, ThreadPerTaskExecutor is created instead of ThreadPoolExecutor")
public class DefaultExecutorServiceManagerTest extends ContextTestSupport {

    @Test
    public void testResolveThreadNameDefaultPattern() {
        String foo = context.getExecutorServiceManager().resolveThreadName("foo");
        String bar = context.getExecutorServiceManager().resolveThreadName("bar");

        assertNotSame(foo, bar);
        assertTrue(foo.startsWith("Camel (" + context.getName() + ") thread "));
        assertTrue(foo.endsWith("foo"));
        assertTrue(bar.startsWith("Camel (" + context.getName() + ") thread "));
        assertTrue(bar.endsWith("bar"));
    }

    @Test
    public void testGetThreadNameCustomPattern() {
        context.getExecutorServiceManager().setThreadNamePattern("##counter# - #name#");
        assertEquals("##counter# - #name#", context.getExecutorServiceManager().getThreadNamePattern());
        String foo = context.getExecutorServiceManager().resolveThreadName("foo");
        String bar = context.getExecutorServiceManager().resolveThreadName("bar");

        assertNotSame(foo, bar);
        assertTrue(foo.startsWith("#"));
        assertTrue(foo.endsWith(" - foo"));
        assertTrue(bar.startsWith("#"));
        assertTrue(bar.endsWith(" - bar"));
    }

    @Test
    public void testGetThreadNameCustomPatternCamelId() {
        context.getExecutorServiceManager().setThreadNamePattern("##camelId# - ##counter# - #name#");
        String foo = context.getExecutorServiceManager().resolveThreadName("foo");
        String bar = context.getExecutorServiceManager().resolveThreadName("bar");

        assertNotSame(foo, bar);
        assertTrue(foo.startsWith("#" + context.getName() + " - #"));
        assertTrue(foo.endsWith(" - foo"));
        assertTrue(bar.startsWith("#" + context.getName() + " - #"));
        assertTrue(bar.endsWith(" - bar"));
    }

    @Test
    public void testGetThreadNameCustomPatternWithDollar() {
        context.getExecutorServiceManager().setThreadNamePattern("Hello - #name#");
        String foo = context.getExecutorServiceManager().resolveThreadName("foo$bar");

        assertEquals("Hello - foo$bar", foo);
    }

    @Test
    public void testGetThreadNameCustomPatternLongName() {
        context.getExecutorServiceManager().setThreadNamePattern("##counter# - #longName#");
        String foo = context.getExecutorServiceManager().resolveThreadName("foo?beer=Carlsberg");
        String bar = context.getExecutorServiceManager().resolveThreadName("bar");

        assertNotSame(foo, bar);
        assertTrue(foo.startsWith("#"));
        assertTrue(foo.endsWith(" - foo?beer=Carlsberg"));
        assertTrue(bar.startsWith("#"));
        assertTrue(bar.endsWith(" - bar"));
    }

    @Test
    public void testGetThreadNameCustomPatternWithParameters() {
        context.getExecutorServiceManager().setThreadNamePattern("##counter# - #name#");
        String foo = context.getExecutorServiceManager().resolveThreadName("foo?beer=Carlsberg");
        String bar = context.getExecutorServiceManager().resolveThreadName("bar");

        assertNotSame(foo, bar);
        assertTrue(foo.startsWith("#"));
        assertTrue(foo.endsWith(" - foo"));
        assertTrue(bar.startsWith("#"));
        assertTrue(bar.endsWith(" - bar"));
    }

    @Test
    public void testGetThreadNameCustomPatternNoCounter() {
        context.getExecutorServiceManager().setThreadNamePattern("Cool #name#");
        String foo = context.getExecutorServiceManager().resolveThreadName("foo");
        String bar = context.getExecutorServiceManager().resolveThreadName("bar");

        assertNotSame(foo, bar);
        assertEquals("Cool foo", foo);
        assertEquals("Cool bar", bar);
    }

    @Test
    public void testGetThreadNameCustomPatternInvalid() {
        context.getExecutorServiceManager().setThreadNamePattern("Cool #xxx#");

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> context.getExecutorServiceManager().resolveThreadName("foo"),
                "Should thrown an exception");

        assertEquals("Pattern is invalid: [Cool #xxx#] in resolved thread name: [Cool #xxx#]", e.getMessage());

        // reset it so we can shutdown properly
        context.getExecutorServiceManager().setThreadNamePattern("Camel Thread #counter# - #name#");
    }

    @Test
    public void testDefaultThreadPool() {
        ExecutorService myPool = context.getExecutorServiceManager().newDefaultThreadPool(this, "myPool");
        assertFalse(myPool.isShutdown());

        // should use default settings
        ThreadPoolExecutor executor = (ThreadPoolExecutor) myPool;
        assertEquals(10, executor.getCorePoolSize());
        assertEquals(20, executor.getMaximumPoolSize());
        assertEquals(60, executor.getKeepAliveTime(TimeUnit.SECONDS));
        assertEquals(1000, executor.getQueue().remainingCapacity());

        context.stop();
        assertTrue(myPool.isShutdown());
    }

    @Test
    public void testDefaultUnboundedQueueThreadPool() {
        ThreadPoolProfile custom = new ThreadPoolProfile("custom");
        custom.setPoolSize(10);
        custom.setMaxPoolSize(30);
        custom.setKeepAliveTime(50L);
        custom.setMaxQueueSize(Integer.MAX_VALUE);

        context.getExecutorServiceManager().setDefaultThreadPoolProfile(custom);
        assertTrue(custom.isDefaultProfile().booleanValue());

        ExecutorService myPool = context.getExecutorServiceManager().newDefaultThreadPool(this, "myPool");
        assertFalse(myPool.isShutdown());

        // should use default settings
        ThreadPoolExecutor executor = (ThreadPoolExecutor) myPool;
        assertEquals(10, executor.getCorePoolSize());
        assertEquals(30, executor.getMaximumPoolSize());
        assertEquals(50, executor.getKeepAliveTime(TimeUnit.SECONDS));
        assertEquals(Integer.MAX_VALUE, executor.getQueue().remainingCapacity());

        context.stop();
        assertTrue(myPool.isShutdown());
    }

    @Test
    public void testDefaultNoMaxQueueThreadPool() {
        ThreadPoolProfile custom = new ThreadPoolProfile("custom");
        custom.setPoolSize(10);
        custom.setMaxPoolSize(30);
        custom.setKeepAliveTime(50L);
        custom.setMaxQueueSize(0);

        context.getExecutorServiceManager().setDefaultThreadPoolProfile(custom);
        assertTrue(custom.isDefaultProfile().booleanValue());

        ExecutorService myPool = context.getExecutorServiceManager().newDefaultThreadPool(this, "myPool");
        assertFalse(myPool.isShutdown());

        // should use default settings
        ThreadPoolExecutor executor = (ThreadPoolExecutor) myPool;
        assertEquals(10, executor.getCorePoolSize());
        assertEquals(30, executor.getMaximumPoolSize());
        assertEquals(50, executor.getKeepAliveTime(TimeUnit.SECONDS));
        assertEquals(0, executor.getQueue().remainingCapacity());

        context.stop();
        assertTrue(myPool.isShutdown());
    }

    @Test
    public void testCustomDefaultThreadPool() {
        ThreadPoolProfile custom = new ThreadPoolProfile("custom");
        custom.setKeepAliveTime(20L);
        custom.setMaxPoolSize(40);
        custom.setPoolSize(5);
        custom.setMaxQueueSize(2000);

        context.getExecutorServiceManager().setDefaultThreadPoolProfile(custom);
        assertTrue(custom.isDefaultProfile().booleanValue());

        ExecutorService myPool = context.getExecutorServiceManager().newDefaultThreadPool(this, "myPool");
        assertFalse(myPool.isShutdown());

        // should use default settings
        ThreadPoolExecutor executor = (ThreadPoolExecutor) myPool;
        assertEquals(5, executor.getCorePoolSize());
        assertEquals(40, executor.getMaximumPoolSize());
        assertEquals(20, executor.getKeepAliveTime(TimeUnit.SECONDS));
        assertEquals(2000, executor.getQueue().remainingCapacity());

        context.stop();
        assertTrue(myPool.isShutdown());
    }

    @Test
    public void testGetThreadPoolProfile() {
        assertNull(context.getExecutorServiceManager().getThreadPoolProfile("foo"));

        ThreadPoolProfile foo = new ThreadPoolProfile("foo");
        foo.setKeepAliveTime(20L);
        foo.setMaxPoolSize(40);
        foo.setPoolSize(5);
        foo.setMaxQueueSize(2000);

        context.getExecutorServiceManager().registerThreadPoolProfile(foo);

        assertSame(foo, context.getExecutorServiceManager().getThreadPoolProfile("foo"));
    }

    @Test
    public void testTwoGetThreadPoolProfile() {
        assertNull(context.getExecutorServiceManager().getThreadPoolProfile("foo"));

        ThreadPoolProfile foo = new ThreadPoolProfile("foo");
        foo.setKeepAliveTime(20L);
        foo.setMaxPoolSize(40);
        foo.setPoolSize(5);
        foo.setMaxQueueSize(2000);

        context.getExecutorServiceManager().registerThreadPoolProfile(foo);

        ThreadPoolProfile bar = new ThreadPoolProfile("bar");
        bar.setKeepAliveTime(40L);
        bar.setMaxPoolSize(5);
        bar.setPoolSize(1);
        bar.setMaxQueueSize(100);

        context.getExecutorServiceManager().registerThreadPoolProfile(bar);

        assertSame(foo, context.getExecutorServiceManager().getThreadPoolProfile("foo"));
        assertSame(bar, context.getExecutorServiceManager().getThreadPoolProfile("bar"));
        assertNotSame(foo, bar);

        assertFalse(context.getExecutorServiceManager().getThreadPoolProfile("foo").isDefaultProfile());
        assertFalse(context.getExecutorServiceManager().getThreadPoolProfile("bar").isDefaultProfile());
    }

    @Test
    public void testGetThreadPoolProfileInheritDefaultValues() {
        assertNull(context.getExecutorServiceManager().getThreadPoolProfile("foo"));
        ThreadPoolProfile foo = new ThreadPoolProfile("foo");
        foo.setMaxPoolSize(40);
        context.getExecutorServiceManager().registerThreadPoolProfile(foo);
        assertSame(foo, context.getExecutorServiceManager().getThreadPoolProfile("foo"));

        ExecutorService executor = context.getExecutorServiceManager().newThreadPool(this, "MyPool", "foo");
        ThreadPoolExecutor tp = assertIsInstanceOf(ThreadPoolExecutor.class, executor);
        assertEquals(40, tp.getMaximumPoolSize());
        // should inherit the default values
        assertEquals(10, tp.getCorePoolSize());
        assertEquals(60, tp.getKeepAliveTime(TimeUnit.SECONDS));
        assertEquals("CallerRuns", tp.getRejectedExecutionHandler().toString());
    }

    @Test
    public void testGetThreadPoolProfileInheritCustomDefaultValues() {
        ThreadPoolProfile newDefault = new ThreadPoolProfile("newDefault");
        newDefault.setKeepAliveTime(30L);
        newDefault.setMaxPoolSize(50);
        newDefault.setPoolSize(5);
        newDefault.setMaxQueueSize(2000);
        newDefault.setRejectedPolicy(ThreadPoolRejectedPolicy.Abort);
        context.getExecutorServiceManager().setDefaultThreadPoolProfile(newDefault);

        assertNull(context.getExecutorServiceManager().getThreadPoolProfile("foo"));
        ThreadPoolProfile foo = new ThreadPoolProfile("foo");
        foo.setMaxPoolSize(25);
        foo.setPoolSize(1);
        context.getExecutorServiceManager().registerThreadPoolProfile(foo);
        assertSame(foo, context.getExecutorServiceManager().getThreadPoolProfile("foo"));

        ExecutorService executor = context.getExecutorServiceManager().newThreadPool(this, "MyPool", "foo");

        ThreadPoolExecutor tp = assertIsInstanceOf(ThreadPoolExecutor.class, executor);
        assertEquals(25, tp.getMaximumPoolSize());
        // should inherit the default values
        assertEquals(1, tp.getCorePoolSize());
        assertEquals(30, tp.getKeepAliveTime(TimeUnit.SECONDS));
        assertEquals("Abort", tp.getRejectedExecutionHandler().toString());
    }

    @Test
    public void testGetThreadPoolProfileInheritCustomDefaultValues2() {
        ThreadPoolProfile newDefault = new ThreadPoolProfile("newDefault");
        // just change the max pool as the default profile should then inherit
        // the old default profile
        newDefault.setMaxPoolSize(50);
        context.getExecutorServiceManager().setDefaultThreadPoolProfile(newDefault);

        assertNull(context.getExecutorServiceManager().getThreadPoolProfile("foo"));
        ThreadPoolProfile foo = new ThreadPoolProfile("foo");
        foo.setPoolSize(1);
        context.getExecutorServiceManager().registerThreadPoolProfile(foo);
        assertSame(foo, context.getExecutorServiceManager().getThreadPoolProfile("foo"));

        ExecutorService executor = context.getExecutorServiceManager().newThreadPool(this, "MyPool", "foo");

        ThreadPoolExecutor tp = assertIsInstanceOf(ThreadPoolExecutor.class, executor);
        assertEquals(1, tp.getCorePoolSize());
        // should inherit the default values
        assertEquals(50, tp.getMaximumPoolSize());
        assertEquals(60, tp.getKeepAliveTime(TimeUnit.SECONDS));
        assertEquals("CallerRuns", tp.getRejectedExecutionHandler().toString());
    }

    @Test
    public void testNewThreadPoolProfile() {
        assertNull(context.getExecutorServiceManager().getThreadPoolProfile("foo"));

        ThreadPoolProfile foo = new ThreadPoolProfile("foo");
        foo.setKeepAliveTime(20L);
        foo.setMaxPoolSize(40);
        foo.setPoolSize(5);
        foo.setMaxQueueSize(2000);

        ExecutorService pool = context.getExecutorServiceManager().newThreadPool(this, "Cool", foo);
        assertNotNull(pool);

        ThreadPoolExecutor tp = assertIsInstanceOf(ThreadPoolExecutor.class, pool);
        assertEquals(20, tp.getKeepAliveTime(TimeUnit.SECONDS));
        assertEquals(40, tp.getMaximumPoolSize());
        assertEquals(5, tp.getCorePoolSize());
        assertFalse(tp.isShutdown());

        context.stop();

        assertTrue(tp.isShutdown());
    }

    @Test
    public void testNewThreadPoolProfileById() {
        assertNull(context.getExecutorServiceManager().getThreadPoolProfile("foo"));

        ThreadPoolProfile foo = new ThreadPoolProfile("foo");
        foo.setKeepAliveTime(20L);
        foo.setMaxPoolSize(40);
        foo.setPoolSize(5);
        foo.setMaxQueueSize(2000);

        context.getExecutorServiceManager().registerThreadPoolProfile(foo);

        ExecutorService pool = context.getExecutorServiceManager().newThreadPool(this, "Cool", "foo");
        assertNotNull(pool);

        ThreadPoolExecutor tp = assertIsInstanceOf(ThreadPoolExecutor.class, pool);
        assertEquals(20, tp.getKeepAliveTime(TimeUnit.SECONDS));
        assertEquals(40, tp.getMaximumPoolSize());
        assertEquals(5, tp.getCorePoolSize());
        assertFalse(tp.isShutdown());

        context.stop();

        assertTrue(tp.isShutdown());
    }

    @Test
    public void testNewThreadPoolMinMax() {
        ExecutorService pool = context.getExecutorServiceManager().newThreadPool(this, "Cool", 5, 10);
        assertNotNull(pool);

        ThreadPoolExecutor tp = assertIsInstanceOf(ThreadPoolExecutor.class, pool);
        assertEquals(60, tp.getKeepAliveTime(TimeUnit.SECONDS));
        assertEquals(10, tp.getMaximumPoolSize());
        assertEquals(5, tp.getCorePoolSize());
        assertFalse(tp.isShutdown());

        context.stop();

        assertTrue(tp.isShutdown());
    }

    @Test
    public void testNewFixedThreadPool() {
        ExecutorService pool = context.getExecutorServiceManager().newFixedThreadPool(this, "Cool", 5);
        assertNotNull(pool);

        ThreadPoolExecutor tp = assertIsInstanceOf(ThreadPoolExecutor.class, pool);
        // a fixed dont use keep alive
        assertEquals(0, tp.getKeepAliveTime(TimeUnit.SECONDS), "keepAliveTime");
        assertEquals(5, tp.getMaximumPoolSize(), "maximumPoolSize");
        assertEquals(5, tp.getCorePoolSize());
        assertFalse(tp.isShutdown());

        context.stop();

        assertTrue(tp.isShutdown());
    }

    @Test
    public void testNewSingleThreadExecutor() {
        ExecutorService pool = context.getExecutorServiceManager().newSingleThreadExecutor(this, "Cool");
        assertNotNull(pool);

        ThreadPoolExecutor tp = assertIsInstanceOf(ThreadPoolExecutor.class, pool);
        // a single dont use keep alive
        assertEquals(0, tp.getKeepAliveTime(TimeUnit.SECONDS), "keepAliveTime");
        assertEquals(1, tp.getMaximumPoolSize(), "maximumPoolSize");
        assertEquals(1, tp.getCorePoolSize());
        assertFalse(tp.isShutdown());

        context.stop();

        assertTrue(tp.isShutdown());
    }

    @Test
    public void testNewScheduledThreadPool() {
        ExecutorService pool = context.getExecutorServiceManager().newScheduledThreadPool(this, "Cool", 5);
        assertNotNull(pool);

        SizedScheduledExecutorService tp = assertIsInstanceOf(SizedScheduledExecutorService.class, pool);
        // a scheduled dont use keep alive
        assertEquals(0, tp.getKeepAliveTime(TimeUnit.SECONDS));
        assertEquals(Integer.MAX_VALUE, tp.getMaximumPoolSize());
        assertEquals(5, tp.getCorePoolSize());
        assertFalse(tp.isShutdown());

        context.stop();

        assertTrue(tp.isShutdown());
    }

    @Test
    public void testNewSingleThreadScheduledExecutor() {
        ExecutorService pool = context.getExecutorServiceManager().newSingleThreadScheduledExecutor(this, "Cool");
        assertNotNull(pool);

        SizedScheduledExecutorService tp = assertIsInstanceOf(SizedScheduledExecutorService.class, pool);
        // a scheduled dont use keep alive
        assertEquals(0, tp.getKeepAliveTime(TimeUnit.SECONDS));
        assertEquals(Integer.MAX_VALUE, tp.getMaximumPoolSize());
        assertEquals(1, tp.getCorePoolSize());
        assertFalse(tp.isShutdown());

        context.stop();

        assertTrue(tp.isShutdown());
    }

    @Test
    public void testNewCachedThreadPool() {
        ExecutorService pool = context.getExecutorServiceManager().newCachedThreadPool(this, "Cool");
        assertNotNull(pool);

        ThreadPoolExecutor tp = assertIsInstanceOf(ThreadPoolExecutor.class, pool);
        assertEquals(60, tp.getKeepAliveTime(TimeUnit.SECONDS));
        assertEquals(Integer.MAX_VALUE, tp.getMaximumPoolSize());
        assertEquals(0, tp.getCorePoolSize());
        assertFalse(tp.isShutdown());

        context.stop();

        assertTrue(tp.isShutdown());
    }

    @Test
    public void testNewScheduledThreadPoolProfileById() {
        assertNull(context.getExecutorServiceManager().getThreadPoolProfile("foo"));

        ThreadPoolProfile foo = new ThreadPoolProfile("foo");
        foo.setKeepAliveTime(20L);
        foo.setMaxPoolSize(40);
        foo.setPoolSize(5);
        foo.setMaxQueueSize(2000);

        context.getExecutorServiceManager().registerThreadPoolProfile(foo);

        ExecutorService pool = context.getExecutorServiceManager().newScheduledThreadPool(this, "Cool", "foo");
        assertNotNull(pool);

        SizedScheduledExecutorService tp = assertIsInstanceOf(SizedScheduledExecutorService.class, pool);
        // a scheduled dont use keep alive
        assertEquals(0, tp.getKeepAliveTime(TimeUnit.SECONDS));
        assertEquals(Integer.MAX_VALUE, tp.getMaximumPoolSize());
        assertEquals(5, tp.getCorePoolSize());
        assertFalse(tp.isShutdown());

        context.stop();

        assertTrue(tp.isShutdown());
    }

    @Test
    public void testNewThread() {
        Thread thread = context.getExecutorServiceManager().newThread("Cool", new Runnable() {
            @Override
            public void run() {
                // noop
            }
        });

        assertNotNull(thread);
        assertTrue(thread.isDaemon());
        assertTrue(thread.getName().contains("Cool"));
    }

    @Disabled("This is a manual test, by looking at the logs")
    @Test
    public void testLongShutdownOfThreadPool() throws Exception {
        final CountDownLatch latch = new CountDownLatch(1);
        ExecutorService pool = context.getExecutorServiceManager().newSingleThreadExecutor(this, "Cool");

        pool.execute(new Runnable() {
            @Override
            public void run() {
                log.info("Starting thread");

                // this should take a long time to shutdown
                try {
                    latch.await(42, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    // ignore
                }

                log.info("Existing thread");
            }
        });

        // sleep a bit before shutting down
        Thread.sleep(3000);

        context.getExecutorServiceManager().shutdown(pool);

        assertTrue(pool.isShutdown());
        assertTrue(pool.isTerminated());
    }

    @Test
    public void testThreadFactoryListener() {
        // custom thread factory
        ThreadFactory myFactory = r -> new Thread(r, "MyFactory");
        // hook custom factory into Camel
        context.getExecutorServiceManager().addThreadFactoryListener(((source, factory) -> myFactory));
        // create thread
        Thread thread = context.getExecutorServiceManager().newThread("Cool", () -> {
            // noop
        });

        assertNotNull(thread);
        assertTrue(thread.isDaemon());
        // should be created by custom factory instead of Camel
        assertTrue(thread.getName().contains("MyFactory"));
    }

    @Test
    public void testThreadFactoryListenerViaRegistry() {
        // create another CamelContext as camelContext is already started in this test-class
        CamelContext c = new DefaultCamelContext();

        // custom thread factory
        ThreadFactory myFactory = r -> new Thread(r, "MyFactory2");
        // hook custom factory into Camel via registry
        ExecutorServiceManager.ThreadFactoryListener listener = (source, factory) -> myFactory;
        c.getRegistry().bind("myListener", listener);
        c.start();

        // create thread
        Thread thread = c.getExecutorServiceManager().newThread("Cool2", () -> {
            // noop
        });

        assertNotNull(thread);
        assertTrue(thread.isDaemon());
        // should be created by custom factory instead of Camel
        assertTrue(thread.getName().contains("MyFactory2"));

        c.stop();
    }

}

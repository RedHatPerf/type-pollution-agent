package io.type.pollution.agent;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicLongFieldUpdater;
import java.util.concurrent.atomic.AtomicReference;

public class TraceInstanceOf {

    private static final long MILLIS = 10;
    private static volatile long GLOBAL_SAMPLING_TICK = System.nanoTime();
    private static final Thread METRONOME = new Thread(() -> {
        final Thread current = Thread.currentThread();
        while (!current.isInterrupted()) {
            try {
                Thread.sleep(MILLIS);
            } catch (InterruptedException e) {
                // let's stop
                return;
            }
            GLOBAL_SAMPLING_TICK = System.nanoTime();
        }

    });

    public static void startMetronome() {
        METRONOME.setDaemon(true);
        METRONOME.setName("type-pollution-metronome");
        METRONOME.start();
    }

    public static final class UpdateCounter {

        private static final AtomicLongFieldUpdater<UpdateCounter> UPDATE_COUNT = AtomicLongFieldUpdater.newUpdater(UpdateCounter.class, "updateCount");

        private static final AtomicLongFieldUpdater<UpdateCounter> SAMPLING_TICK_UPDATER = AtomicLongFieldUpdater.newUpdater(UpdateCounter.class, "lastSamplingTick");
        private volatile long updateCount;
        private volatile long lastSamplingTick = System.nanoTime();
        private final AtomicReference<Class> lastSeenInterface = new AtomicReference<>();
        private final CopyOnWriteArraySet<String> topStackTraces = new CopyOnWriteArraySet<>();
        private final CopyOnWriteArraySet<Class> interfacesSeen = new CopyOnWriteArraySet<>();

        private void lazyUpdateCount(Class seenClazz) {
            final AtomicReference<Class> lastSeenInterface = this.lastSeenInterface;
            final Class lastSeen = lastSeenInterface.get();
            if (!seenClazz.equals(lastSeen)) {
                // not important if we loose some samples
                lastSeenInterface.lazySet(seenClazz);
                if (lastSeen == null) {
                    UPDATE_COUNT.lazySet(this, 1);
                } else {
                    UPDATE_COUNT.lazySet(this, updateCount + 1);
                }
                if (lastSeen != null) {
                    interfacesSeen.add(seenClazz);
                    final long tick = lastSamplingTick;
                    final long globalTick = GLOBAL_SAMPLING_TICK;
                    if (tick - globalTick < 0) {
                        // move forward our tick
                        if (SAMPLING_TICK_UPDATER.compareAndSet(this, tick, globalTick)) {
                            final String stackFrame = StackWalker.getInstance().walk(stream -> stream.skip(3).findFirst()).get().toString();
                            topStackTraces.add(stackFrame);
                        }
                    }
                }
            }
        }

        public static class Snapshot implements Comparable<Snapshot> {
            public final Class clazz;
            public final Class[] seen;
            public final String[] topStackTraces;
            public final long updateCount;

            private Snapshot(Class clazz, Class[] seen, String[] topStackTraces, long updateCount) {
                this.clazz = clazz;
                this.seen = seen;
                this.topStackTraces = topStackTraces;
                this.updateCount = updateCount;
            }

            @Override
            public int compareTo(Snapshot o) {
                return Long.compare(updateCount, o.updateCount);
            }
        }

        private Snapshot mementoOf(Class clazz) {
            return new UpdateCounter.Snapshot(clazz, interfacesSeen.toArray(new Class[0]), topStackTraces.toArray(new String[0]), updateCount);
        }

    }

    private static final ConcurrentHashMap<Class, UpdateCounter> COUNTER_CACHE = new ConcurrentHashMap<>();


    public static boolean traceInstanceOf(Object o, Class interfaceClazz) {
        final boolean result = interfaceClazz.isInstance(o);
        if (!result) {
            return false;
        }
        final Class concreteClass = o.getClass();
        // unnecessary tracing
        if (!interfaceClazz.isInterface()
                || concreteClass.isInterface()
                || concreteClass.isArray()
                || concreteClass.isAnnotation()) {
            return true;
        }
        UpdateCounter counter = COUNTER_CACHE.get(concreteClass);
        if (counter == null) {
            try {
                final UpdateCounter newCounter = new UpdateCounter();
                counter = COUNTER_CACHE.putIfAbsent(concreteClass, newCounter);
                if (counter == null) {
                    counter = newCounter;
                }
            } catch (Throwable ignore) {
                return true;
            }
        }
        counter.lazyUpdateCount(interfaceClazz);
        return true;
    }

    public static void traceCheckcast(Object o, Class interfaceClazz) {
        if (!interfaceClazz.isInstance(o)) {
            return;
        }
        final Class concreteClass = o.getClass();
        // unnecessary tracing
        if (!interfaceClazz.isInterface()
                || concreteClass.isInterface()
                || concreteClass.isArray()
                || concreteClass.isAnnotation()) {
            return;
        }
        UpdateCounter counter = COUNTER_CACHE.get(concreteClass);
        if (counter == null) {
            try {
                final UpdateCounter newCounter = new UpdateCounter();
                counter = COUNTER_CACHE.putIfAbsent(concreteClass, newCounter);
                if (counter == null) {
                    counter = newCounter;
                }
            } catch (Throwable ignore) {
                return;
            }
        }
        counter.lazyUpdateCount(interfaceClazz);
    }

    public static Collection<UpdateCounter.Snapshot> orderedSnapshot() {
        List<UpdateCounter.Snapshot> snapshots = new ArrayList<>(COUNTER_CACHE.size());
        COUNTER_CACHE.forEach((aClass, updateCounter) -> {
            if (updateCounter.updateCount > 1) {
                snapshots.add(updateCounter.mementoOf(aClass));
            }
        });
        snapshots.sort(Comparator.reverseOrder());
        return snapshots;
    }

}

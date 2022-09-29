package io.type.pollution.agent;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public class TraceInstanceOf {

    public static final class UpdateCounter {

        private final AtomicLong updateCount = new AtomicLong();
        private final AtomicReference<Class<?>> lastSeenInterface = new AtomicReference<>();

        private void lazyUpdateCount(Class<?> seenClazz) {
            final AtomicReference<Class<?>> lastSeenInterface = this.lastSeenInterface;
            if (!seenClazz.equals(lastSeenInterface.get())) {
                final AtomicLong updateCount = this.updateCount;
                // not important if we loose some samples
                lastSeenInterface.lazySet(seenClazz);
                updateCount.lazySet(updateCount.get() + 1);
            }
        }

        public static class Snapshot implements Comparable<Snapshot> {
            public final Class<?> clazz;
            public final long updateCount;

            private Snapshot(Class<?> clazz, long updateCount) {
                this.clazz = clazz;
                this.updateCount = updateCount;
            }

            @Override
            public int compareTo(Snapshot o) {
                return Long.compare(updateCount, o.updateCount);
            }
        }

        private Snapshot mementoOf(Class<?> clazz) {
            return new UpdateCounter.Snapshot(clazz, updateCount.get());
        }

    }

    private static final ConcurrentHashMap<Class<?>, UpdateCounter> COUNTER_CACHE = new ConcurrentHashMap<>();

    /**
     * o instanceof interfaceClazz
     */
    public static void instanceOf(Object o, Class<?> interfaceClazz, boolean result) {
        if (o == null) {
            return;
        }
        // maybe others?
        final Class<?> concreteClass = o.getClass();
        // maybe others are not interesting
        if (!interfaceClazz.isInterface()
                || concreteClass.isInterface()
                || concreteClass.isArray()
                || concreteClass.isAnnotation()) {
            return;
        }
        if (result) {
            UpdateCounter counter = COUNTER_CACHE.get(concreteClass);
            if (counter == null) {
                final UpdateCounter newCounter = new UpdateCounter();
                counter = COUNTER_CACHE.putIfAbsent(concreteClass, newCounter);
                if (counter == null) {
                    counter = newCounter;
                }
            }
            counter.lazyUpdateCount(interfaceClazz);
        }
    }

    public static Collection<UpdateCounter.Snapshot> orderedSnapshot() {
        List<UpdateCounter.Snapshot> snapshots = new ArrayList<>(COUNTER_CACHE.size());
        COUNTER_CACHE.forEach((aClass, updateCounter) -> {
            snapshots.add(updateCounter.mementoOf(aClass));
        });
        snapshots.sort(Comparator.reverseOrder());
        return snapshots;
    }

}

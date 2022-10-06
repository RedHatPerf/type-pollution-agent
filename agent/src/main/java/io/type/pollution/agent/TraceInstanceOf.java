package io.type.pollution.agent;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public class TraceInstanceOf {

    public static final class UpdateCounter {

        private final AtomicLong updateCount = new AtomicLong();
        private final AtomicReference<Class> lastSeenInterface = new AtomicReference<>();
        private final CopyOnWriteArraySet<String> topStackTraces = new CopyOnWriteArraySet<>();
        private final CopyOnWriteArraySet<Class> interfacesSeen = new CopyOnWriteArraySet<>();

        private void lazyUpdateCount(Class seenClazz) {
            final AtomicReference<Class> lastSeenInterface = this.lastSeenInterface;
            final Class lastSeen = lastSeenInterface.get();
            if (!seenClazz.equals(lastSeen)) {
                final AtomicLong updateCount = this.updateCount;
                // not important if we loose some samples
                lastSeenInterface.lazySet(seenClazz);
                if (lastSeen == null) {
                    updateCount.lazySet(1);
                } else {
                    updateCount.lazySet(updateCount.get() + 1);
                }
                if (lastSeen != null) {
                    final String stackFrame = StackWalker.getInstance().walk(stream -> stream.skip(3).findFirst()).get().toString();
                    topStackTraces.add(stackFrame);
                    interfacesSeen.add(seenClazz);
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
            return new UpdateCounter.Snapshot(clazz, interfacesSeen.toArray(new Class[0]), topStackTraces.toArray(new String[0]), updateCount.get());
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
            if (updateCounter.updateCount.get() > 1) {
                snapshots.add(updateCounter.mementoOf(aClass));
            }
        });
        snapshots.sort(Comparator.reverseOrder());
        return snapshots;
    }

}

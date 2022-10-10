package io.type.pollution.agent;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicLongFieldUpdater;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

public class TraceInstanceOf {

    public static final class UpdateCounter {

        private static final AtomicReferenceFieldUpdater<UpdateCounter, Class> LAST_SEEN_INTERFACE_UPDATER =
                AtomicReferenceFieldUpdater.newUpdater(UpdateCounter.class, Class.class, "lastSeenInterface");

        private static final AtomicLongFieldUpdater<UpdateCounter> UPDATE_COUNT =
                AtomicLongFieldUpdater.newUpdater(UpdateCounter.class, "updateCount");
        private final Class clazz;
        private volatile long updateCount;
        private volatile Class lastSeenInterface = null;

        private final CopyOnWriteArraySet<Trace> traces = new CopyOnWriteArraySet<>();

        private final ThreadLocal<Trace> TRACE = ThreadLocal.withInitial(Trace::new);

        public static class Trace {
            private Class seenClazz;
            private String trace;
            public Trace() {

            }
            public Trace with(Class seenClazz, String trace) {
                this.seenClazz = seenClazz;
                this.trace = trace;
                return this;
            }

            public Trace clear() {
                this.seenClazz = seenClazz;
                this.trace = trace;
                return this;
            }

            @Override
            public boolean equals(final Object o) {
                if (this == o) return true;
                if (o == null || getClass() != o.getClass()) return false;

                final Trace trace1 = (Trace) o;

                if (!seenClazz.equals(trace1.seenClazz)) return false;
                return trace.equals(trace1.trace);
            }

            @Override
            public int hashCode() {
                int result = seenClazz.hashCode();
                result = 31 * result + trace.hashCode();
                return result;
            }
        }

        private UpdateCounter(Class clazz) {
            this.clazz = clazz;
        }

        private void lazyUpdateCount(Class seenClazz, String trace) {
            final Class lastSeen = lastSeenInterface;
            if (!seenClazz.equals(lastSeen)) {
                // not important if we loose some samples
                LAST_SEEN_INTERFACE_UPDATER.lazySet(this, seenClazz);
                if (lastSeen == null) {
                    UPDATE_COUNT.lazySet(this, 1);
                } else {
                    UPDATE_COUNT.lazySet(this, updateCount + 1);
                }
                if (lastSeen != null) {
                    Trace pooledTrace = TRACE.get().with(seenClazz, trace);
                    boolean added = false;
                    try {
                        added = traces.add(pooledTrace);
                    } finally {
                        if (added) {
                            // cannot reuse anymore, replace it
                            TRACE.set(new Trace());
                        } else {
                            pooledTrace.clear();
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

        private Snapshot mementoOf() {
            final int size = traces.size();
            final Set<Class> interfacesSeen = new HashSet<>(size);
            final Set<String> topStackTraces = new HashSet<>(size);
            for (Trace traces : traces) {
                interfacesSeen.add(traces.seenClazz);
                topStackTraces.add(traces.trace);
            }
            return new UpdateCounter.Snapshot(clazz, interfacesSeen.toArray(new Class[0]), topStackTraces.toArray(new String[0]), updateCount);
        }

    }

    private static final AppendOnlyList<UpdateCounter> COUNTERS = new AppendOnlyList<>();
    private static final ClassValue<UpdateCounter> COUNTER_CACHE = new ClassValue<>() {
        @Override
        protected UpdateCounter computeValue(Class<?> aClass) {
            final UpdateCounter updateCounter = new UpdateCounter(aClass);
            COUNTERS.add(updateCounter);
            return updateCounter;
        }
    };

    public static boolean traceIsInstance(Class interfaceClazz, Object o, String trace) {
        if (!interfaceClazz.isInstance(o)) {
            return false;
        }
        // unnecessary tracing
        if (!interfaceClazz.isInterface()) {
            return true;
        }
        COUNTER_CACHE.get(o.getClass()).lazyUpdateCount(interfaceClazz, trace);
        return true;
    }

    public static boolean traceIsAssignableFrom(Class interfaceClazz, Class oClazz, boolean result, String trace) {
        if (!result) {
            return false;
        }
        if (!interfaceClazz.isInterface()) {
            return true;
        }
        COUNTER_CACHE.get(oClazz).lazyUpdateCount(interfaceClazz, trace);
        return true;
    }

    public static void traceCast(Class interfaceClazz, Object o, String trace) {
        if (!interfaceClazz.isInterface()) {
            return;
        }
        if (!interfaceClazz.isInstance(o)) {
            return;
        }
        COUNTER_CACHE.get(o.getClass()).lazyUpdateCount(interfaceClazz, trace);
    }

    public static boolean traceInstanceOf(Object o, Class interfaceClazz, String trace) {
        if (!interfaceClazz.isInstance(o)) {
            return false;
        }
        if (!interfaceClazz.isInterface()) {
            return true;
        }
        COUNTER_CACHE.get(o.getClass()).lazyUpdateCount(interfaceClazz, trace);
        return true;
    }

    public static void traceCheckcast(Object o, Class interfaceClazz, String trace) {
        if (!interfaceClazz.isInterface()) {
            return;
        }
        if (!interfaceClazz.isInstance(o)) {
            return;
        }
        COUNTER_CACHE.get(o.getClass()).lazyUpdateCount(interfaceClazz, trace);
    }

    private static class TyeProfile {
        int typesSeen = 0;
    }

    private static void populateTraces(IdentityHashMap<String, TyeProfile> tracesPerConcreteType, String[] topStackTraces) {
        // update how many concrete types are seen per trace
        for (String trace : topStackTraces) {
            TyeProfile typeProfile = tracesPerConcreteType.get(trace);
            if (typeProfile == null) {
                typeProfile = new TyeProfile();
                tracesPerConcreteType.put(trace, typeProfile);
            }
            typeProfile.typesSeen++;
        }
    }

    private static <T> boolean fastRemove(ArrayList<T> elements, int index) {
        final int size = elements.size();
        final int last = size - 1;
        if (index == last) {
            elements.remove(index);
            return false;
        }
        elements.set(index, elements.remove(last));
        return true;
    }

    /**
     * THIS IS A VERY CONSERVATIVE STRATEGY TO REMOVE TYPES!
     * We remove a type if all the traces that have made flip its last seen interface checkcast/instanceof
     * have *ever* seen just 1 type ie itself.
     * Reality is way more complex and probably there are others, more aggressive, logic
     * to clean the statistics.
     **/
    private static void cleanupStatistics(IdentityHashMap<String, TyeProfile> tracesPerConcreteType, ArrayList<UpdateCounter.Snapshot> snapshots) {
        int pos = 0;
        for (int i = 0, size = snapshots.size(); i < size; i++) {
            final UpdateCounter.Snapshot snapshot = snapshots.get(pos);
            boolean safe = true;
            for (String trace : snapshot.topStackTraces) {
                final TyeProfile typeProfile = tracesPerConcreteType.get(trace);
                if (typeProfile.typesSeen > 1) {
                    safe = false;
                    break;
                }
            }
            if (safe) {
                if (!fastRemove(snapshots, pos)) {
                    break;
                }
            } else {
                pos++;
            }
        }
    }

    public static Collection<UpdateCounter.Snapshot> orderedSnapshot(final boolean cleanup) {
        final int size = (int) COUNTERS.size();
        ArrayList<UpdateCounter.Snapshot> snapshots = new ArrayList<>(size);
        final IdentityHashMap<String, TyeProfile> tracesPerConcreteType = cleanup ? new IdentityHashMap<>(size) : null;
        COUNTERS.forEach(updateCounter -> {
            if (updateCounter.updateCount > 1 && updateCounter.traces.size() > 1) {
                final UpdateCounter.Snapshot snapshot = updateCounter.mementoOf();
                // the update count and trace collecting is lazy; let's skip malformed cases
                snapshots.add(snapshot);
                if (cleanup) {
                    populateTraces(tracesPerConcreteType, snapshot.topStackTraces);
                }
            }
        });
        if (cleanup) {
            cleanupStatistics(tracesPerConcreteType, snapshots);
        }
        snapshots.sort(Comparator.reverseOrder());
        // collect
        return snapshots;
    }

}

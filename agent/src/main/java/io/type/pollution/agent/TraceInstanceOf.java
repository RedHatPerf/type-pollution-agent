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
        private final CopyOnWriteArraySet<String> topStackTraces = new CopyOnWriteArraySet<>();

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
                    final boolean firstTimeAdded = topStackTraces.add(trace);
                    if (firstTimeAdded) {
                        INTERFACE_PER_TRACE.putIfAbsent(trace, seenClazz);
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

        private Snapshot memento() {
            final String[] traces = topStackTraces.toArray(new String[0]);
            final Set<Class> interfacesTypes = new HashSet<>(traces.length);
            for (String trace : traces) {
                interfacesTypes.add(INTERFACE_PER_TRACE.get(trace));
            }
            return new UpdateCounter.Snapshot(clazz, interfacesTypes.toArray(new Class[0]), topStackTraces.toArray(new String[0]), updateCount);
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

    private static final ConcurrentHashMap<String, Class> INTERFACE_PER_TRACE = new ConcurrentHashMap<>();


    public static boolean traceInstanceOf(Object o, Class interfaceClazz, String trace) {
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
        COUNTER_CACHE.get(concreteClass).lazyUpdateCount(interfaceClazz, trace);
        return true;
    }

    public static void traceCheckcast(Object o, Class interfaceClazz, String trace) {
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
        COUNTER_CACHE.get(concreteClass).lazyUpdateCount(interfaceClazz, trace);
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
            if (updateCounter.updateCount > 1 && updateCounter.topStackTraces.size() > 1) {
                final UpdateCounter.Snapshot snapshot = updateCounter.memento();
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

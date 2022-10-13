package io.type.pollution.agent;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLongFieldUpdater;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

public class TraceInstanceOf {

    private static final long MILLIS = 10;

    private static volatile long GLOBAL_SAMPLING_TICK = System.nanoTime();
    private static final AtomicBoolean METRONOME_STARTED = new AtomicBoolean();

    // not ideal perf-wise:
    // a good candidate to perform one-shot changing checks is
    // https://docs.oracle.com/en/java/javase/11/docs/api/java.base/java/lang/invoke/SwitchPoint.html
    private static final AtomicBoolean TRACING_STARTED = new AtomicBoolean();
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

    public static void startTracing(final int seconds) {
        if (seconds <= 0) {
            startTracing();
        } else {
            final long start = System.nanoTime();
            final Thread startTracingThread = new Thread(() -> {
                final long timeToStartThread = System.nanoTime() - start;
                final long remaningBeforeStartTracing =
                        TimeUnit.SECONDS.toNanos(seconds) - timeToStartThread;
                if (remaningBeforeStartTracing > 0) {
                    try {
                        TimeUnit.NANOSECONDS.sleep(remaningBeforeStartTracing);
                        startTracing();
                    } catch (InterruptedException ignore) {
                        // we're stopping
                    }
                }
            });
            startTracingThread.setName("start-tracing");
            startTracingThread.setDaemon(true);
            startTracingThread.start();
        }
    }

    private static void startTracing() {
        TRACING_STARTED.compareAndSet(false, true);
    }

    public static void startMetronome() {
        if (METRONOME_STARTED.compareAndSet(false, true)) {
            METRONOME.setDaemon(true);
            METRONOME.setName("type-pollution-metronome");
            METRONOME.start();
        }
    }

    public static final class UpdateCounter {

        private static final class StackTraceArrayList extends ArrayList<StackTraceElement> {

            public StackTraceArrayList(int capacity) {
                super(capacity);
            }
        }

        private static final AtomicReferenceFieldUpdater<UpdateCounter, Class> LAST_SEEN_INTERFACE_UPDATER =
                AtomicReferenceFieldUpdater.newUpdater(UpdateCounter.class, Class.class, "lastSeenInterface");
        private static final AtomicLongFieldUpdater<UpdateCounter> SAMPLING_TICK_UPDATER =
                AtomicLongFieldUpdater.newUpdater(UpdateCounter.class, "lastSamplingTick");

        private final Class clazz;
        private volatile Class lastSeenInterface = null;
        private volatile long lastSamplingTick = System.nanoTime();

        private final ConcurrentHashMap<Trace, TraceData> traces = new ConcurrentHashMap<>();
        private static final ThreadLocal<Trace> TRACE = ThreadLocal.withInitial(Trace::new);

        public static class Trace {
            private Class interfaceClazz;
            private String trace;

            private int hashCode;

            public Trace() {

            }

            public Trace with(Class interfaceClazz, String trace) {
                this.interfaceClazz = interfaceClazz;
                this.trace = trace;
                return this;
            }

            public Trace clear() {
                this.interfaceClazz = null;
                this.trace = null;
                return this;
            }

            @Override
            public boolean equals(final Object o) {
                if (this == o) return true;
                if (o == null || getClass() != o.getClass()) return false;

                final Trace trace1 = (Trace) o;

                if (!interfaceClazz.equals(trace1.interfaceClazz)) return false;
                return trace.equals(trace1.trace);
            }

            @Override
            public int hashCode() {
                int result = interfaceClazz.hashCode();
                result = 31 * result + trace.hashCode();
                return result;
            }
        }

        public static class TraceData {

            private static final ThreadLocal<StackTraceArrayList> FULL_STACK_TRACE = new ThreadLocal<>();

            private static final AtomicLongFieldUpdater<TraceData> UPDATE_COUNT =
                    AtomicLongFieldUpdater.newUpdater(TraceData.class, "updateCount");

            private volatile long updateCount;
            private final CopyOnWriteArraySet<StackTraceArrayList> sampledStackTraces = new CopyOnWriteArraySet<>();

            public void weakIncrementUpdateCount() {
                UPDATE_COUNT.lazySet(this, updateCount + 1);
            }

            private static StackTraceArrayList acquireStackTraceListOf(int capacity) {
                StackTraceArrayList list = FULL_STACK_TRACE.get();
                if (list == null) {
                    list = new StackTraceArrayList(capacity);
                    FULL_STACK_TRACE.set(list);
                }
                list.ensureCapacity(capacity);
                return list;
            }

            private static void releaseStackTraceArrayList() {
                FULL_STACK_TRACE.set(null);
            }

            public boolean addFullStackTrace() {
                StackTraceElement[] stackTraces = Thread.currentThread().getStackTrace();
                final StackTraceArrayList fullStackTraces = acquireStackTraceListOf(stackTraces.length);
                boolean addedFullStackSample = false;
                try {
                    // this is not nice :( we KNOW which level we are so can hard-code this
                    // but is brittle and it depends where the trace collection happens
                    for (int i = 4; i < stackTraces.length; i++) {
                        fullStackTraces.add(stackTraces[i]);
                    }
                    addedFullStackSample = sampledStackTraces.add(fullStackTraces);
                } catch (Throwable ignore) {

                }
                if (addedFullStackSample) {
                    // cannot reuse it
                    releaseStackTraceArrayList();
                } else {
                    // keep on reusing it
                    fullStackTraces.clear();
                }
                return addedFullStackSample;
            }

        }

        private UpdateCounter(Class clazz) {
            this.clazz = clazz;
        }

        public void onSuccessfullyTypeCheck(Class interfaceClazz, String trace) {
            final Class lastSeen = lastSeenInterface;
            if (interfaceClazz.equals(lastSeen)) {
                return;
            }
            // ok to lose some sample
            LAST_SEEN_INTERFACE_UPDATER.lazySet(this, interfaceClazz);
            if (lastSeen != null) {
                final Trace pooledTrace = TRACE.get().with(interfaceClazz, trace);
                final boolean added;
                TraceData data = traces.get(pooledTrace);
                if (data == null) {
                    TraceData newData = new TraceData();
                    data = traces.putIfAbsent(pooledTrace, newData);
                    if (data == null) {
                        // cannot reuse the pooled trace anymore!
                        added = true;
                        data = newData;
                    } else {
                        added = false;
                    }
                } else {
                    added = false;
                }
                if (added) {
                    // replace it!
                    TRACE.set(new Trace());
                } else {
                    pooledTrace.clear();
                }
                data.weakIncrementUpdateCount();
                if (METRONOME_STARTED.get()) {
                    final long tick = lastSamplingTick;
                    final long globalTick = GLOBAL_SAMPLING_TICK;
                    if (tick - globalTick < 0) {
                        // move forward our tick
                        if (SAMPLING_TICK_UPDATER.compareAndSet(this, tick, globalTick)) {
                            data.addFullStackTrace();
                        }
                    }
                }
            }
        }

        public long updateCount() {
            long count = 0;
            for (Map.Entry<Trace, TraceData> trace : traces.entrySet()) {
                count += trace.getValue().updateCount;
            }
            return count;
        }

        public static class Snapshot implements Comparable<Snapshot> {

            public static class TraceSnapshot {

                public static class ClassUpdateCount {
                    public final Class interfaceClazz;
                    public final long updateCount;

                    private ClassUpdateCount(final Class interfaceClazz, final long updateCount) {
                        this.interfaceClazz = interfaceClazz;
                        this.updateCount = updateCount;
                    }
                }

                public final String trace;
                public final ClassUpdateCount[] interfaceSeenCounters;

                private TraceSnapshot(final String trace, final ClassUpdateCount[] interfaceSeenCounters) {
                    this.trace = trace;
                    this.interfaceSeenCounters = interfaceSeenCounters;
                }
            }

            public final Class clazz;
            public final Class[] seen;
            public final TraceSnapshot[] traces;
            public final StackTraceElement[][] fullStackFrames;
            public final long updateCount;

            private Snapshot(Class clazz, Class[] seen, TraceSnapshot[] traces, StackTraceElement[][] fullStackFrame) {
                this.clazz = clazz;
                this.seen = seen;
                this.fullStackFrames = fullStackFrame;
                this.traces = traces;
                this.updateCount = updateCount(traces);
            }

            private static long updateCount(TraceSnapshot[] traces) {
                long count = 0;
                for (TraceSnapshot trace : traces) {
                    for (TraceSnapshot.ClassUpdateCount counter : trace.interfaceSeenCounters) {
                        count += counter.updateCount;
                    }
                }
                return count;
            }

            @Override
            public int compareTo(Snapshot o) {
                return Long.compare(updateCount, o.updateCount);
            }
        }

        private Snapshot mementoOf() {
            final int tracesCount = traces.size();
            final Map<String, List<Snapshot.TraceSnapshot.ClassUpdateCount>> topStackTraces = new HashMap<>(tracesCount);
            final Set<StackTraceArrayList> fullStackFrames = new HashSet<>(tracesCount);
            class Counter {
                long value;
            }
            final Map<Class, Counter> interfaceCounters = new HashMap<>();
            traces.forEach((trace, traceData) -> {
                for (StackTraceArrayList fullStackTrace : traceData.sampledStackTraces) {
                    fullStackFrames.add(fullStackTrace);
                }
                topStackTraces.computeIfAbsent(trace.trace, t -> new ArrayList<>(1))
                        .add(new Snapshot.TraceSnapshot.ClassUpdateCount(trace.interfaceClazz, traceData.updateCount));
                // TODO add to existing update count to interfaceCounters
                interfaceCounters.computeIfAbsent(trace.interfaceClazz, t -> new Counter()).value += traceData.updateCount;
            });
            final Snapshot.TraceSnapshot[] traceSnapshots = new Snapshot.TraceSnapshot[topStackTraces.size()];
            int i = 0;
            for (Map.Entry<String, List<Snapshot.TraceSnapshot.ClassUpdateCount>> topStackTrace : topStackTraces.entrySet()) {
                Snapshot.TraceSnapshot.ClassUpdateCount[] classUpdateCounts = topStackTrace.getValue()
                        .toArray(new Snapshot.TraceSnapshot.ClassUpdateCount[0]);
                // order update count(s) based on
                Arrays.sort(classUpdateCounts,
                        Comparator.<Snapshot.TraceSnapshot.ClassUpdateCount>comparingLong(classUpdateCount -> classUpdateCount.updateCount).reversed());
                traceSnapshots[i] = new Snapshot.TraceSnapshot(topStackTrace.getKey(), classUpdateCounts);
                i++;
            }
            // order trace snapshot(s) based on total (ie sum) update count
            Arrays.sort(traceSnapshots, Comparator.<Snapshot.TraceSnapshot>comparingLong(traceSnapshot -> {
                long totalUpdateCount = 0;
                for (Snapshot.TraceSnapshot.ClassUpdateCount classUpdateCount : traceSnapshot.interfaceSeenCounters) {
                    totalUpdateCount += classUpdateCount.updateCount;
                }
                return totalUpdateCount;
            }).reversed());
            final Class[] interfaceClasses = new Class[interfaceCounters.size()];
            int j = 0;
            for (Map.Entry<Class, Counter> interfaceCounter : interfaceCounters.entrySet()) {
                interfaceClasses[j] = interfaceCounter.getKey();
                j++;
            }
            Arrays.sort(interfaceClasses,
                    Comparator.<Class>comparingLong(aClass -> interfaceCounters.get(aClass).value).reversed());
            // TODO order interfaces seen based on sum (across different traces) of update counts
            final StackTraceElement[][] fullStackTraces = new StackTraceElement[fullStackFrames.size()][];
            int z = 0;
            for (StackTraceArrayList fullStackFrame : fullStackFrames) {
                fullStackTraces[z] = fullStackFrame.toArray(new StackTraceElement[0]);
                z++;
            }
            return new UpdateCounter.Snapshot(clazz, interfaceClasses, traceSnapshots,
                    fullStackTraces);
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
        if (!isTracingStarted()) {
            return true;
        }
        // unnecessary tracing
        if (!interfaceClazz.isInterface()) {
            return true;
        }
        COUNTER_CACHE.get(o.getClass()).onSuccessfullyTypeCheck(interfaceClazz, trace);
        return true;
    }

    public static boolean traceIsAssignableFrom(Class interfaceClazz, Class oClazz, boolean result, String trace) {
        if (!result) {
            return false;
        }
        if (!isTracingStarted()) {
            return true;
        }
        if (!interfaceClazz.isInterface()) {
            return true;
        }
        COUNTER_CACHE.get(oClazz).onSuccessfullyTypeCheck(interfaceClazz, trace);
        return true;
    }

    public static void traceCast(Class interfaceClazz, Object o, String trace) {
        if (!isTracingStarted()) {
            return;
        }
        if (!interfaceClazz.isInterface()) {
            return;
        }
        if (!interfaceClazz.isInstance(o)) {
            return;
        }
        COUNTER_CACHE.get(o.getClass()).onSuccessfullyTypeCheck(interfaceClazz, trace);
    }

    public static boolean traceInstanceOf(Object o, Class interfaceClazz, String trace) {
        if (!interfaceClazz.isInstance(o)) {
            return false;
        }
        if (!isTracingStarted()) {
            return true;
        }
        if (!interfaceClazz.isInterface()) {
            return true;
        }
        COUNTER_CACHE.get(o.getClass()).onSuccessfullyTypeCheck(interfaceClazz, trace);
        return true;
    }

    private static boolean isTracingStarted() {
        return TRACING_STARTED.get();
    }

    public static void traceCheckcast(Object o, Class interfaceClazz, String trace) {
        if (!isTracingStarted()) {
            return;
        }
        if (!interfaceClazz.isInterface()) {
            return;
        }
        if (!interfaceClazz.isInstance(o)) {
            return;
        }
        COUNTER_CACHE.get(o.getClass()).onSuccessfullyTypeCheck(interfaceClazz, trace);
    }

    private static class TyeProfile {
        int typesSeen = 0;
    }

    private static void populateTraces(IdentityHashMap<String, TyeProfile> tracesPerConcreteType, UpdateCounter.Snapshot.TraceSnapshot[] topStackTraces) {
        // update how many concrete types are seen per trace
        for (UpdateCounter.Snapshot.TraceSnapshot trace : topStackTraces) {
            TyeProfile typeProfile = tracesPerConcreteType.get(trace.trace);
            if (typeProfile == null) {
                typeProfile = new TyeProfile();
                tracesPerConcreteType.put(trace.trace, typeProfile);
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
            for (UpdateCounter.Snapshot.TraceSnapshot trace : snapshot.traces) {
                final TyeProfile typeProfile = tracesPerConcreteType.get(trace.trace);
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
            if (updateCounter.updateCount() > 1 && updateCounter.traces.size() > 1) {
                final UpdateCounter.Snapshot snapshot = updateCounter.mementoOf();
                // the update count and trace collecting is lazy; let's skip malformed cases
                snapshots.add(snapshot);
                if (cleanup) {
                    populateTraces(tracesPerConcreteType, snapshot.traces);
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

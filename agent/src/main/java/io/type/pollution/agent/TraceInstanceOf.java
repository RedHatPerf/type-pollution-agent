package io.type.pollution.agent;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLongFieldUpdater;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

public class TraceInstanceOf {

    private static volatile long GLOBAL_SAMPLING_TICK = System.nanoTime();
    private static final AtomicInteger METRONOME_PERIOD_MS = new AtomicInteger(-1);

    // not ideal perf-wise:
    // a good candidate to perform one-shot changing checks is
    // https://docs.oracle.com/en/java/javase/11/docs/api/java.base/java/lang/invoke/SwitchPoint.html
    private static final AtomicBoolean TRACING_STARTED = new AtomicBoolean();
    private static final Thread METRONOME = new Thread(() -> {
        // this isn't supposed to change
        final int samplingPeriod = METRONOME_PERIOD_MS.get();
        final Thread current = Thread.currentThread();
        while (!current.isInterrupted()) {
            try {
                Thread.sleep(samplingPeriod);
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

    public static void startMetronome(int samplingPeriod) {
        if (METRONOME_PERIOD_MS.compareAndSet(-1, samplingPeriod) && samplingPeriod > 0) {
            METRONOME.setDaemon(true);
            METRONOME.setName("type-pollution-metronome");
            METRONOME.start();
        }
    }

    public static final class MissTraceCounter extends TraceCounter {


        private MissTraceCounter(Class clazz) {
            super(clazz);
        }

        public void onTypeCheckMiss(Class interfaceClazz, String trace) {
            updateTraceCount(interfaceClazz, trace);
        }
    }

    public static final class TypePollutionTraceCounter extends TraceCounter {
        private static final AtomicReferenceFieldUpdater<TypePollutionTraceCounter, Class> LAST_SEEN_INTERFACE_UPDATER =
                AtomicReferenceFieldUpdater.newUpdater(TypePollutionTraceCounter.class, Class.class, "lastSeenInterface");

        private volatile Class lastSeenInterface = null;

        private TypePollutionTraceCounter(Class clazz) {
            super(clazz);
        }

        public void onTypeCheckHit(Class interfaceClazz, String trace) {
            final Class lastSeen = lastSeenInterface;
            if (interfaceClazz.equals(lastSeen)) {
                return;
            }
            // ok to lose some sample
            LAST_SEEN_INTERFACE_UPDATER.lazySet(this, interfaceClazz);
            if (lastSeen != null) {
                updateTraceCount(interfaceClazz, trace);
            }
        }
    }

    public static class TraceCounter {

        private static final class StackTraceArrayList extends ArrayList<StackTraceElement> {

            public StackTraceArrayList(int capacity) {
                super(capacity);
            }
        }


        private static final AtomicLongFieldUpdater<TraceCounter> SAMPLING_TICK_UPDATER =
                AtomicLongFieldUpdater.newUpdater(TraceCounter.class, "lastSamplingTick");

        private final Class clazz;
        private volatile long lastSamplingTick = System.nanoTime();
        private final ConcurrentHashMap<Trace, TraceData> traces = new ConcurrentHashMap<>();
        private static final ThreadLocal<Trace> TRACE = ThreadLocal.withInitial(Trace::new);

        public static class Trace {
            private Class interfaceClazz;
            private String trace;

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

            private static final AtomicLongFieldUpdater<TraceData> COUNT_UPDATER =
                    AtomicLongFieldUpdater.newUpdater(TraceData.class, "count");

            private volatile long count;
            private final CopyOnWriteArraySet<StackTraceArrayList> sampledStackTraces = new CopyOnWriteArraySet<>();

            public void weakIncrementUpdateCount() {
                COUNT_UPDATER.lazySet(this, count + 1);
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
                // this is not nice :( we KNOW which level we are so can hard-code this
                // but is brittle, and it depends on where the trace collection happens
                final int START_STACK = 5;
                StackTraceElement[] stackTraces = Thread.currentThread().getStackTrace();
                final int stackTraceMaxDepth;
                if (Agent.FULL_STACK_TRACES_LIMIT <= 0) {
                    stackTraceMaxDepth = stackTraces.length;
                } else {
                    stackTraceMaxDepth = Math.min(Agent.FULL_STACK_TRACES_LIMIT + START_STACK, stackTraces.length);
                }
                final StackTraceArrayList fullStackTraces = acquireStackTraceListOf(stackTraceMaxDepth);
                boolean addedFullStackSample = false;
                try {
                    for (int i = START_STACK; i < stackTraceMaxDepth; i++) {
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

        private TraceCounter(Class clazz) {
            this.clazz = clazz;
        }

        protected final void updateTraceCount(Class interfaceClazz, String trace) {
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
            final int samplingPeriod = METRONOME_PERIOD_MS.get();
            if (samplingPeriod >= 0) {
                if (samplingPeriod == 0) {
                    data.addFullStackTrace();
                } else {
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

        public long count() {
            long count = 0;
            for (Map.Entry<Trace, TraceData> trace : traces.entrySet()) {
                count += trace.getValue().count;
            }
            return count;
        }

        public static class Snapshot implements Comparable<Snapshot> {

            public static class TraceSnapshot {

                public static class ClassCount {
                    public final Class interfaceClazz;
                    public final long count;

                    private ClassCount(final Class interfaceClazz, final long count) {
                        this.interfaceClazz = interfaceClazz;
                        this.count = count;
                    }
                }

                public final String trace;
                public final ClassCount[] interfaceSeenCounters;

                private TraceSnapshot(final String trace, final ClassCount[] interfaceSeenCounters) {
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
                    for (TraceSnapshot.ClassCount counter : trace.interfaceSeenCounters) {
                        count += counter.count;
                    }
                }
                return count;
            }

            @Override
            public int compareTo(Snapshot o) {
                return Long.compare(updateCount, o.updateCount);
            }
        }

        private static Snapshot.TraceSnapshot[] buildOrderedTraceSnapshots(Map<String, List<Snapshot.TraceSnapshot.ClassCount>> topStackTraces) {
            final Snapshot.TraceSnapshot[] traceSnapshots = new Snapshot.TraceSnapshot[topStackTraces.size()];
            int i = 0;
            for (Map.Entry<String, List<Snapshot.TraceSnapshot.ClassCount>> topStackTrace : topStackTraces.entrySet()) {
                Snapshot.TraceSnapshot.ClassCount[] classCounts = topStackTrace.getValue()
                        .toArray(new Snapshot.TraceSnapshot.ClassCount[0]);
                // order update count(s) based on
                Arrays.sort(classCounts,
                        Comparator.<Snapshot.TraceSnapshot.ClassCount>comparingLong(classCount -> classCount.count).reversed());
                traceSnapshots[i] = new Snapshot.TraceSnapshot(topStackTrace.getKey(), classCounts);
                i++;
            }
            // order trace snapshot(s) based on total (ie sum) update count
            Arrays.sort(traceSnapshots, Comparator.<Snapshot.TraceSnapshot>comparingLong(traceSnapshot -> {
                long totalUpdateCount = 0;
                for (Snapshot.TraceSnapshot.ClassCount classCount : traceSnapshot.interfaceSeenCounters) {
                    totalUpdateCount += classCount.count;
                }
                return totalUpdateCount;
            }).reversed());
            return traceSnapshots;
        }

        private static class Counter {
            long value;
        }

        private static Class[] buildOrderedInterfaceClasses(final Map<Class, Counter> interfaceCounters) {
            final Class[] interfaceClasses = new Class[interfaceCounters.size()];
            int j = 0;
            for (Map.Entry<Class, Counter> interfaceCounter : interfaceCounters.entrySet()) {
                interfaceClasses[j] = interfaceCounter.getKey();
                j++;
            }
            Arrays.sort(interfaceClasses,
                    Comparator.<Class>comparingLong(aClass -> interfaceCounters.get(aClass).value).reversed());
            return interfaceClasses;
        }

        private static StackTraceElement[][] buildUnorderedFullStackTraces(Set<StackTraceArrayList> fullStackFrames) {
            final StackTraceElement[][] fullStackTraces = new StackTraceElement[fullStackFrames.size()][];
            int z = 0;
            for (StackTraceArrayList fullStackFrame : fullStackFrames) {
                fullStackTraces[z] = fullStackFrame.toArray(new StackTraceElement[0]);
                z++;
            }
            return fullStackTraces;
        }

        public Snapshot snapshot() {
            final int tracesCount = traces.size();
            if (tracesCount == 0) {
                return null;
            }
            final Map<String, List<Snapshot.TraceSnapshot.ClassCount>> topStackTraces = new HashMap<>(tracesCount);
            final Set<StackTraceArrayList> fullStackFrames = new HashSet<>(tracesCount);

            final Map<Class, Counter> interfaceCounters = new HashMap<>();
            traces.forEach((trace, traceData) -> {
                for (StackTraceArrayList fullStackTrace : traceData.sampledStackTraces) {
                    fullStackFrames.add(fullStackTrace);
                }
                topStackTraces.computeIfAbsent(trace.trace, t -> new ArrayList<>(1))
                        .add(new Snapshot.TraceSnapshot.ClassCount(trace.interfaceClazz, traceData.count));
                interfaceCounters.computeIfAbsent(trace.interfaceClazz, t -> new Counter()).value += traceData.count;
            });
            final Snapshot.TraceSnapshot[] traceSnapshots = buildOrderedTraceSnapshots(topStackTraces);
            final Class[] interfaceClasses = buildOrderedInterfaceClasses(interfaceCounters);
            final StackTraceElement[][] fullStackTraces = buildUnorderedFullStackTraces(fullStackFrames);
            return new TraceCounter.Snapshot(clazz, interfaceClasses, traceSnapshots,
                    fullStackTraces);
        }

    }

    private static final AppendOnlyList<TypePollutionTraceCounter> TYPE_POLLUTION_COUNTERS = new AppendOnlyList<>();
    private static final AppendOnlyList<MissTraceCounter> MISS_COUNTERS = new AppendOnlyList<>();

    private static final ClassValue<TypePollutionTraceCounter> TYPE_POLLUTION_COUNTER_CACHE = new ClassValue<>() {
        @Override
        protected TypePollutionTraceCounter computeValue(Class<?> aClass) {
            final TypePollutionTraceCounter counter = new TypePollutionTraceCounter(aClass);
            TYPE_POLLUTION_COUNTERS.add(counter);
            return counter;
        }
    };

    private static final ClassValue<MissTraceCounter> MISS_COUNTER_CACHE = new ClassValue<>() {
        @Override
        protected MissTraceCounter computeValue(Class<?> aClass) {
            final MissTraceCounter counter = new MissTraceCounter(aClass);
            MISS_COUNTERS.add(counter);
            return counter;
        }
    };

    public static boolean traceIsInstance(Class interfaceClazz, Object o, String trace) {
        if (!interfaceClazz.isInstance(o)) {
            if (o != null && isTracingStarted() && interfaceClazz.isInterface()) {
                MISS_COUNTER_CACHE.get(o.getClass()).onTypeCheckMiss(interfaceClazz, trace);
            }
            return false;
        }
        if (!isTracingStarted()) {
            return true;
        }
        // unnecessary tracing
        if (!interfaceClazz.isInterface()) {
            return true;
        }
        TYPE_POLLUTION_COUNTER_CACHE.get(o.getClass()).onTypeCheckHit(interfaceClazz, trace);
        return true;
    }

    public static boolean traceIsAssignableFrom(Class interfaceClazz, Class oClazz, boolean result, String trace) {
        if (!result) {
            if (isTracingStarted() && interfaceClazz.isInterface()) {
                MISS_COUNTER_CACHE.get(oClazz).onTypeCheckMiss(interfaceClazz, trace);
            }
            return false;
        }
        if (!isTracingStarted()) {
            return true;
        }
        if (!interfaceClazz.isInterface()) {
            return true;
        }
        TYPE_POLLUTION_COUNTER_CACHE.get(oClazz).onTypeCheckHit(interfaceClazz, trace);
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
        TYPE_POLLUTION_COUNTER_CACHE.get(o.getClass()).onTypeCheckHit(interfaceClazz, trace);
    }

    public static boolean traceInstanceOf(Object o, Class interfaceClazz, String trace) {
        if (!interfaceClazz.isInstance(o)) {
            if (o!= null && isTracingStarted() && interfaceClazz.isInterface()) {
                MISS_COUNTER_CACHE.get(o.getClass()).onTypeCheckMiss(interfaceClazz, trace);
            }
            return false;
        }
        if (!isTracingStarted()) {
            return true;
        }
        if (!interfaceClazz.isInterface()) {
            return true;
        }
        TYPE_POLLUTION_COUNTER_CACHE.get(o.getClass()).onTypeCheckHit(interfaceClazz, trace);
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
        TYPE_POLLUTION_COUNTER_CACHE.get(o.getClass()).onTypeCheckHit(interfaceClazz, trace);
    }

    private static Collection<TraceCounter.Snapshot> orderedCountersSnapshots(AppendOnlyList<? extends TraceCounter> counters, final int minUpdateCount) {
        final int size = (int) counters.size();
        ArrayList<TraceCounter.Snapshot> snapshots = new ArrayList<>(size);
        counters.forEach(traceCounter -> {
            final int minCount = Math.max(1, minUpdateCount);
            if (traceCounter.count() > minCount) {
                final TraceCounter.Snapshot snapshot = traceCounter.snapshot();
                if (snapshot != null) {
                    snapshots.add(snapshot);
                }
            }
        });
        snapshots.sort(Comparator.reverseOrder());
        // collect
        return snapshots;
    }

    public static Collection<TraceCounter.Snapshot> orderedTypePollutionCountersSnapshot(final int minUpdateCount) {
        return orderedCountersSnapshots(TYPE_POLLUTION_COUNTERS, minUpdateCount);
    }

    public static Collection<TraceCounter.Snapshot> orderedMissCountersSnapshot(final int minUpdateCount) {
        return orderedCountersSnapshots(MISS_COUNTERS, minUpdateCount);
    }

}

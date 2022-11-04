package io.type.pollution.benchmarks;

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.Objects;
import java.util.concurrent.TimeUnit;

@State(Scope.Thread)
@Measurement(iterations = 10, time = 200, timeUnit = TimeUnit.MILLISECONDS)
@Warmup(iterations = 10, time = 200, timeUnit = TimeUnit.MILLISECONDS)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Fork(2)
/**
 * To have more fun, run this with:
 * -prof perfasm
 * -prof jfr
 * -prof "async:output=flamegraph;dir=/tmp;libPath=<async-profiler path>/libasyncProfiler.so"
 *
 * and
 *
 * --jvmArgs="-javaagent:agent/target/type-pollution-agent-0.1-SNAPSHOT.jar"
 *
 * Interesting case is:
 * -p typePollution=true -p fixed=false
 */
public class RequireNonNullCheckcastScalability {
    private Context ctx;

    @Param({"false", "true"})
    public boolean typePollution;

    @Param({"false", "true"})
    public boolean fixed;

    @Setup
    public void init(Blackhole bh) {
        if (typePollution) {
            ctx = new NonDuplicatedContext();
            // let's warm it enough to get it compiled with C2 (by default)
            for (int i = 0; i < 11000; i++) {
                boolean result = fixed ? fixedIsDuplicated(ctx) : wrongIsDuplicated(ctx);
                bh.consume(result);
            }
            // deopt on warmup
        }
        ctx = new DuplicatedContext();
    }

    private static boolean wrongIsDuplicated(Context ctx) {
        return ContextUtil.isDuplicatedContext(ctx);
    }

    private static boolean fixedIsDuplicated(Context ctx) {
        return Objects.requireNonNull((InternalContext) ctx).isDuplicated();
    }

    @Benchmark
    @Threads(1)
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    public boolean isDuplicated1() {
        return isDuplicated();
    }

    @Benchmark
    @Threads(2)
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    public boolean isDuplicated2() {
        return isDuplicated();
    }

    private boolean isDuplicated() {
        if (fixed) {
            return fixedIsDuplicated(ctx);
        }
        return wrongIsDuplicated(ctx);
    }
}
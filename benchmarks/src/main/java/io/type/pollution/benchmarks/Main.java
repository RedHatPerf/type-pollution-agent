package io.type.pollution.benchmarks;

import org.openjdk.jmh.annotations.CompilerControl;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class Main {
    private static final int STACK_DEPTH = 8;

    public static void main(String[] args) {
        // type pollution :(
        var ctx = new NonDuplicatedContext();
        for (int i = 0; i < 11000; i++) {
            applicationStackBase(ctx);
        }
        int numThreads = Integer.parseInt(args[0]);
        ExecutorService es = Executors.newFixedThreadPool(numThreads);
        for (int i = 0; i < numThreads; i++) {
            es.submit(() -> {
                var dupCtx = new DuplicatedContext();
                while (!Thread.currentThread().isInterrupted()) {
                    applicationStackBase(dupCtx);
                }
            });
        }
        es.shutdown();
    }

    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    public static boolean applicationStackBase(Context ctx) {
        return applicationStack(ctx, STACK_DEPTH);
    }

    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    public static boolean applicationStack(Context ctx, int depth) {
        if (depth == 1) {
            return ContextUtil.isDuplicatedContext(ctx);
        } else {
            depth--;
            return applicationStack(ctx, depth);
        }
    }
}

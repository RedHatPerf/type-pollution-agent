package io.type.pollution.agent;

import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.AsmVisitorWrapper;
import net.bytebuddy.asm.TypeConstantAdjustment;
import net.bytebuddy.description.field.FieldDescription;
import net.bytebuddy.description.field.FieldList;
import net.bytebuddy.description.method.MethodList;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.implementation.Implementation;
import net.bytebuddy.jar.asm.ClassWriter;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.pool.TypePool;

import java.lang.instrument.Instrumentation;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static net.bytebuddy.matcher.ElementMatchers.*;

public class Agent {

    private static final boolean ENABLE_FULL_STACK_TRACES =  Boolean.getBoolean("io.type.pollution.full.traces");

    private static final int FULL_STACK_TRACES_SAMPLING_PERIOD_MS = Integer.getInteger("io.type.pollution.full.traces.ms", 0);
    static final int FULL_STACK_TRACES_LIMIT =  Integer.getInteger("io.type.pollution.full.traces.limit", 20);
    private static final int TYPE_UPDATE_COUNT_MIN =  Integer.getInteger("io.type.pollution.count.min", 10);
    private static final int TRACING_DELAY_SECS =  Integer.getInteger("io.type.pollution.delay", 0);
    private static final Long REPORT_INTERVAL_SECS =  Long.getLong("io.type.pollution.report.interval");


    public static void premain(String agentArgs, Instrumentation inst) {
        if (ENABLE_FULL_STACK_TRACES) {
            TraceInstanceOf.startMetronome(FULL_STACK_TRACES_SAMPLING_PERIOD_MS);
        }
        TraceInstanceOf.startTracing(TRACING_DELAY_SECS);
        
        if (REPORT_INTERVAL_SECS != null) {
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r);
                t.setDaemon(true);
                t.setName("type-pollution-periodic-report");
                return t;
            }).scheduleWithFixedDelay(Agent::printReport, TRACING_DELAY_SECS + REPORT_INTERVAL_SECS, REPORT_INTERVAL_SECS, TimeUnit.SECONDS);
        }

        Runtime.getRuntime().addShutdownHook(new Thread(Agent::printReport));

        final String[] agentArgsValues = agentArgs == null ? null : agentArgs.split(",");
        ElementMatcher.Junction<? super TypeDescription> acceptedTypes = any();
        if (agentArgsValues != null && agentArgsValues.length > 0) {
            for (String startWith : agentArgsValues)
                acceptedTypes = acceptedTypes.and(nameStartsWith(startWith));
        }
        new AgentBuilder.Default()
                .with(AgentBuilder.Listener.StreamWriting.toSystemError().withErrorsOnly())
                .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                .with(AgentBuilder.LambdaInstrumentationStrategy.ENABLED)
                .with(AgentBuilder.InitializationStrategy.NoOp.INSTANCE)
                .type(acceptedTypes
                        .and(not(nameStartsWith("net.bytebuddy.")))
                        .and(not(nameStartsWith("com.sun")))
                        .and(not(nameStartsWith("io.type.pollution.agent"))))
                .transform((builder,
                            typeDescription,
                            classLoader,
                            module,
                            protectionDomain) -> builder
                        .visit(TypeConstantAdjustment.INSTANCE)
                        .visit(new AsmVisitorWrapper.AbstractBase() {

                    @Override
                    public int mergeWriter(int flags) {
                        return flags | ClassWriter.COMPUTE_FRAMES;
                    }

                    @Override
                    public int mergeReader(int flags) {
                        return flags;
                    }

                    @Override
                    public net.bytebuddy.jar.asm.ClassVisitor wrap(TypeDescription instrumentedType,
                                                                   net.bytebuddy.jar.asm.ClassVisitor classVisitor,
                                                                   Implementation.Context implementationContext,
                                                                   TypePool typePool,
                                                                   FieldList<FieldDescription.InDefinedShape> fields,
                                                                   MethodList<?> methods,
                                                                   int writerFlags, int readerFlags) {
                        return new ByteBuddyUtils.ByteBuddyTypePollutionClassVisitor(net.bytebuddy.jar.asm.Opcodes.ASM9, classVisitor);
                    }
                })).installOn(inst);
    }

    private static void printReport() {
        AtomicInteger rowId = new AtomicInteger();
        StringBuilder summary = new StringBuilder("--------------------------\nType Pollution Statistics:\n");
        TraceInstanceOf.orderedSnapshot(TYPE_UPDATE_COUNT_MIN).forEach(snapshot -> {
            summary.append("--------------------------\n");
            summary.append(rowId.incrementAndGet()).append(":\t").append(snapshot.clazz.getName()).append('\n');
            summary.append("Count:\t").append(snapshot.updateCount).append('\n');
            summary.append("Types:\n");
            for (Class<?> seen : snapshot.seen) {
                summary.append("\t").append(seen.getName()).append('\n');
            }
            summary.append("Traces:\n");
            for (TraceInstanceOf.UpdateCounter.Snapshot.TraceSnapshot stack : snapshot.traces) {
                summary.append("\t").append(stack.trace).append('\n');
                for (TraceInstanceOf.UpdateCounter.Snapshot.TraceSnapshot.ClassUpdateCount count : stack.interfaceSeenCounters) {
                    summary.append("\t\tclass: ").append(count.interfaceClazz.getName()).append('\n');
                    summary.append("\t\tcount: ").append(count.updateCount).append('\n');
                }
            }
            if (ENABLE_FULL_STACK_TRACES) {
                summary.append("Full Traces:\n");
                for (StackTraceElement[] fullFrames : snapshot.fullStackFrames) {
                    summary.append("\t--------------------------\n");
                    for (StackTraceElement frame : fullFrames) {
                        summary.append("\t").append(frame).append('\n');
                    }
                }
            }
        });
        if (rowId.get() > 0) {
            summary.append("--------------------------\n");
            System.out.println(summary);
        }

    }

}


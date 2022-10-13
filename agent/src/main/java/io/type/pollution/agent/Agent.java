package io.type.pollution.agent;

import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.AsmVisitorWrapper;
import net.bytebuddy.description.field.FieldDescription;
import net.bytebuddy.description.field.FieldList;
import net.bytebuddy.description.method.MethodList;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.implementation.Implementation;
import net.bytebuddy.jar.asm.ClassWriter;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.pool.TypePool;

import java.lang.instrument.Instrumentation;

import static net.bytebuddy.matcher.ElementMatchers.*;

public class Agent {

    private static final boolean ENABLE_STATISTICS_CLEANUP =  Boolean.getBoolean("io.type.pollution.cleanup");
    private static final boolean ENABLE_FULL_STACK_TRACES =  Boolean.getBoolean("io.type.pollution.full.traces");

    static final int FULL_STACK_TRACES_LIMIT =  Integer.getInteger("io.type.pollution.full.traces.limit", 20);
    private static final int TYPE_UPDATE_COUNT_MIN =  Integer.getInteger("io.type.pollution.count.min", 10);
    private static final int TRACING_DELAY_SECS =  Integer.getInteger("io.type.pollution.delay", 0);



    public static void premain(String agentArgs, Instrumentation inst) {
        if (ENABLE_FULL_STACK_TRACES) {
            TraceInstanceOf.startMetronome();
        }
        TraceInstanceOf.startTracing(TRACING_DELAY_SECS);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            StringBuilder summary = new StringBuilder();
            summary.append("--------------------------\n");
            summary.append("Type Pollution Statistics:\n");
            class MutableInt {
                int rowId = 0;
            }
            MutableInt mutableInt = new MutableInt();
            TraceInstanceOf.orderedSnapshot(ENABLE_STATISTICS_CLEANUP, TYPE_UPDATE_COUNT_MIN).forEach(snapshot -> {
                mutableInt.rowId++;
                summary.append("--------------------------\n");
                summary.append(mutableInt.rowId).append(":\t").append(snapshot.clazz.getName()).append('\n');
                summary.append("Count:\t").append(snapshot.updateCount).append('\n');
                summary.append("Types:\n");
                for (Class seen : snapshot.seen) {
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
                    int i = 1;
                    for (StackTraceElement[] fullFrames : snapshot.fullStackFrames) {
                        summary.append("\t--------------------------\n");
                        i++;
                        for (StackTraceElement frame : fullFrames) {
                            summary.append("\t").append(frame).append('\n');
                        }
                    }
                }
            });
            if (mutableInt.rowId > 0) {
                summary.append("--------------------------\n");
                System.out.println(summary);
            }
        }));
        final String[] agentArgsValues = agentArgs == null ? null : agentArgs.split(",");
        ElementMatcher.Junction<? super TypeDescription> acceptedTypes = any();
        if (agentArgsValues != null && agentArgsValues.length > 0) {
            for (String startWith : agentArgsValues)
                acceptedTypes = acceptedTypes.and(nameStartsWith(startWith));
        }
        new AgentBuilder.Default()
                .with(AgentBuilder.Listener.StreamWriting.toSystemError().withErrorsOnly())
                .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                .with(AgentBuilder.InitializationStrategy.NoOp.INSTANCE)
                .type(acceptedTypes
                        .and(not(nameStartsWith("net.bytebuddy.")))
                        .and(not(nameStartsWith("com.sun")))
                        .and(not(nameStartsWith("io.type.pollution.agent"))))
                .transform((builder,
                            typeDescription,
                            classLoader,
                            module,
                            protectionDomain) -> builder.visit(new AsmVisitorWrapper.AbstractBase() {

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
}


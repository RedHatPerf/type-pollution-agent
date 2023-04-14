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

import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static net.bytebuddy.matcher.ElementMatchers.*;

public class Agent {

    private static final boolean ENABLE_FULL_STACK_TRACES = Boolean.getBoolean("io.type.pollution.full.traces");

    private static final String FILE_DUMP = System.getProperty("io.type.pollution.file");

    private static final int FULL_STACK_TRACES_SAMPLING_PERIOD_MS = Integer.getInteger("io.type.pollution.full.traces.ms", 0);
    static final int FULL_STACK_TRACES_LIMIT = Integer.getInteger("io.type.pollution.full.traces.limit", 20);
    private static final int TYPE_UPDATE_COUNT_MIN = Integer.getInteger("io.type.pollution.count.min", 10);
    private static final int TYPE_MISS_COUNT_MIN = Integer.getInteger("io.type.pollution.miss.count.min", 1);
    private static final int TRACING_DELAY_SECS = Integer.getInteger("io.type.pollution.delay", 0);
    private static final Long REPORT_INTERVAL_SECS = Long.getLong("io.type.pollution.report.interval");
    private static final boolean ENABLE_LAMBDA_INSTRUMENTATION = Boolean.getBoolean("io.type.pollution.lambda");

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
            }).scheduleWithFixedDelay(Agent::printLiveReport, TRACING_DELAY_SECS + REPORT_INTERVAL_SECS, REPORT_INTERVAL_SECS, TimeUnit.SECONDS);
        }

        Runtime.getRuntime().addShutdownHook(new Thread(Agent::printFinalReport));

        final String[] agentArgsValues = agentArgs == null ? null : agentArgs.split(",");
        ElementMatcher.Junction<? super TypeDescription> acceptedTypes = any();
        if (agentArgsValues != null && agentArgsValues.length > 0) {
            for (String startWith : agentArgsValues)
                acceptedTypes = acceptedTypes.and(nameStartsWith(startWith));
        }
        new AgentBuilder.Default()
                .with(AgentBuilder.Listener.StreamWriting.toSystemError().withErrorsOnly())
                .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                .with(ENABLE_LAMBDA_INSTRUMENTATION ?
                        AgentBuilder.LambdaInstrumentationStrategy.ENABLED :
                        AgentBuilder.LambdaInstrumentationStrategy.DISABLED)
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

    private static void printFinalReport() {
        printReport(true);
    }

    private static void printLiveReport() {
        printReport(false);
    }

    private static final DateTimeFormatter REPORT_TIMESTAMP = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss");
    private static FileChannel DUMP;
    private static ByteBuffer DUMP_TMP_BUFFER;
    private static boolean DUMP_ERROR;

    private static boolean LAST_REPORT = false;

    private static CharSequence reportOf(Collection<TraceInstanceOf.TraceCounter.Snapshot> counters) {
        if (counters.isEmpty()) {
            return "";
        }
        StringBuilder report = new StringBuilder();
        int rowId = 0;
        for (TraceInstanceOf.TraceCounter.Snapshot counter : counters) {
            report.append("--------------------------\n");
            rowId++;
            report.append(rowId).append(":\t").append(counter.clazz.getName()).append('\n');
            report.append("Count:\t").append(counter.updateCount).append('\n');
            report.append("Types:\n");
            for (Class<?> seen : counter.seen) {
                report.append("\t").append(seen.getName()).append('\n');
            }
            report.append("Traces:\n");
            for (TraceInstanceOf.TraceCounter.Snapshot.TraceSnapshot stack : counter.traces) {
                report.append("\t").append(stack.trace).append('\n');
                for (TraceInstanceOf.TraceCounter.Snapshot.TraceSnapshot.ClassCount count : stack.interfaceSeenCounters) {
                    report.append("\t\tclass: ").append(count.interfaceClazz.getName()).append('\n');
                    report.append("\t\tcount: ").append(count.count).append('\n');
                }
            }
            if (ENABLE_FULL_STACK_TRACES) {
                report.append("Full Traces:\n");
                for (StackTraceElement[] fullFrames : counter.fullStackFrames) {
                    report.append("\t--------------------------\n");
                    for (StackTraceElement frame : fullFrames) {
                        report.append("\t").append(frame).append('\n');
                    }
                }
            }
        }
        return report;
    }

    private static void printReport(boolean last) {
        if (LAST_REPORT) {
            return;
        }
        if (last) {
            LAST_REPORT = true;
        }
        StringBuilder summary = new StringBuilder("--------------------------\nType Check Statistics:\n--------------------------\n");
        summary.append("Date:\t").append(REPORT_TIMESTAMP.format(LocalDateTime.now())).append('\n');
        summary.append("Last:\t").append(last).append('\n');
        CharSequence typePollutionReport = reportOf(TraceInstanceOf.orderedTypePollutionCountersSnapshot(TYPE_UPDATE_COUNT_MIN));
        if (typePollutionReport.length() > 0) {
            summary.append("--------------------------\nType Pollution:\n");
            summary.append(typePollutionReport);
        }
        CharSequence missReport = reportOf(TraceInstanceOf.orderedMissCountersSnapshot(TYPE_MISS_COUNT_MIN));
        if (missReport.length() > 0) {
            summary.append("--------------------------\nMiss:\n");
            summary.append(missReport);
        }
        boolean emptyReports = typePollutionReport.length() == 0 && missReport.length() == 0;
        if (!emptyReports) {
            summary.append("--------------------------\n");
            if (DUMP_ERROR || FILE_DUMP == null) {
                System.out.println(summary);
                return;
            }
            if (DUMP == null) {
                try {
                    DUMP = FileChannel.open(Paths.get(FILE_DUMP), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                    DUMP_TMP_BUFFER = ByteBuffer.allocateDirect(summary.length());
                } catch (IOException e) {
                    System.err.println("ERROR while creating the Type Pollution Statistics dump to " + FILE_DUMP + " due to: " + e);
                    DUMP_ERROR = true;
                    DUMP_TMP_BUFFER = null;
                    DUMP = null;
                    System.out.println(summary);
                    return;
                }
            }
            if (DUMP_TMP_BUFFER.capacity() < summary.length()) {
                DUMP_TMP_BUFFER = ByteBuffer.allocateDirect(summary.length());
            }
            DUMP_TMP_BUFFER.clear().limit(summary.length());
            // latin encoding rocks for the report, let's keep it simple
            for (int i = 0; i < summary.length(); i++) {
                DUMP_TMP_BUFFER.put(i, (byte) summary.charAt(i));
            }
            try {
                DUMP.write(DUMP_TMP_BUFFER);
            } catch (IOException e) {
                System.err.println("ERROR while dumping the Type Pollution Statistics to " + FILE_DUMP + " due to: " + e);
                DUMP_ERROR = true;
                DUMP_TMP_BUFFER = null;
                if (!closeDump()) {
                    System.out.println(summary);
                }
                return;
            }
            if (last) {
                if (!closeDump()) {
                    System.out.println(summary);
                }
            }
        }
    }

    private static boolean closeDump() {
        try {
            DUMP.close();
            return true;
        } catch (IOException ex) {
            DUMP_ERROR = true;
            DUMP_TMP_BUFFER = null;
            System.err.println("ERROR while closing the Type Pollution Statistics dump on " + FILE_DUMP + " due to: " + ex);
            return false;
        } finally {
            DUMP = null;
        }
    }

}


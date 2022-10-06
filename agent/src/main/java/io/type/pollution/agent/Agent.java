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

    public static void premain(String agentArgs, Instrumentation inst) {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            StringBuilder summary = new StringBuilder();
            summary.append("Class,UpdateCount\n");
            TraceInstanceOf.orderedSnapshot().forEach(snapshot -> {
                summary.append(snapshot.clazz.getName() + "," + snapshot.updateCount + "\n");
            });
            System.out.println(summary);
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
                .type(acceptedTypes
                        .and(not(nameStartsWith("net.bytebuddy.")))
                        .and(not(nameStartsWith("com.sun")))
                        .and(not(named("io.type.pollution.agent.TraceInstanceOf"))))
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


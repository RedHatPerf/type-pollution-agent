package io.type.pollution.agent;

import org.objectweb.asm.commons.InstructionAdapter;
import org.objectweb.asm.*;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.lang.instrument.Instrumentation;
import java.lang.instrument.UnmodifiableClassException;
import java.security.ProtectionDomain;
import java.util.ArrayList;

public class Agent {

    public static void premain(String agentArgs, Instrumentation inst) throws UnmodifiableClassException {
        startAgent(agentArgs, inst, false);
    }

    public static void agentmain(String agentArgs, Instrumentation inst) throws UnmodifiableClassException {
        startAgent(agentArgs, inst, true);
    }

    /**
     * This prevent
     */
    private static boolean safeFilter(String s, String[] toInstrument) {
        if (s.startsWith("java/")) {
            return false;
        }
        if (s.startsWith("jdk/")) {
            return false;
        }
        if (s.startsWith("sun/")) {
            return false;
        }
        if (s.startsWith("com/sun/")) {
            return false;
        }
        if (s.startsWith("javassist/")) {
            return false;
        }
        if (s.equals("io/type/pollution/agent/TraceInstanceOf")) {
            return false;
        }
        if (s.startsWith("org/jboss/logmanager")) {
            return false;
        }
        if (s.startsWith("io/quarkus/bootstrap")) {
            return false;
        }
        if (toInstrument != null && toInstrument.length > 0) {
            boolean matches = false;
            for (String i : toInstrument) {
                if (s.startsWith(i)) {
                    matches = true;
                    break;
                }
            }
            if (!matches) {
                return false;
            }
        }
        return true;
    }

    public static void startAgent(String agentArgs, Instrumentation inst, boolean loadClasses) throws UnmodifiableClassException {
        final String[] toInstrument = agentArgs == null ? null : agentArgs.replace(".", "/").split(",");
        if (loadClasses) {
            final Class[] classes = inst.getAllLoadedClasses();
            final ArrayList<Class> filtered = new ArrayList<>();
            for (Class clazz : classes) {
                if (safeFilter(clazz.getName(), toInstrument)) {
                    filtered.add(clazz);
                }
            }
            if (!filtered.isEmpty()) {
                final ClassFileTransformer transformer = transformer();
                inst.addTransformer(transformer);
                try {
                    inst.retransformClasses(filtered.toArray(new Class[0]));
                } finally {
                    inst.removeTransformer(transformer);
                }
            }
        }
        inst.addTransformer(transformerWith(toInstrument));
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            StringBuilder summary = new StringBuilder();
            summary.append("Class,UpdateCount\n");
            TraceInstanceOf.orderedSnapshot().forEach(snapshot -> {
                summary.append(snapshot.clazz.getName() + "," + snapshot.updateCount + "\n");
            });
            System.out.println(summary);
        }));
    }

    public static ClassFileTransformer transformer() {
        return transformerWith(null);
    }

    public static ClassFileTransformer transformerWith(final String[] toInstrument) {
        return new ClassFileTransformer() {

            @Override
            public byte[] transform(ClassLoader classLoader, final String s, Class<?> aClass, ProtectionDomain protectionDomain, byte[] bytes) throws IllegalClassFormatException {
                if (!safeFilter(s, toInstrument)) {
                    return bytes;
                }
                ClassReader classReader = new ClassReader(bytes);
                ClassWriter classWriter = new ClassWriter(classReader, ClassWriter.COMPUTE_FRAMES);
                classReader.accept(new TypePollutionClassVisitor(Opcodes.ASM5, classWriter), ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
                return classWriter.toByteArray();
            }
        };
    }

    private static class TypePollutionInstructionAdapter extends InstructionAdapter {

        protected TypePollutionInstructionAdapter(int api, MethodVisitor methodVisitor) {
            super(api, methodVisitor);
        }

        @Override
        public void checkcast(final Type type) {
            mv.visitInsn(Opcodes.DUP);
            mv.visitLdcInsn(type);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                    Type.getInternalName(TraceInstanceOf.class),
                    "traceCheckcast",
                    "(Ljava/lang/Object;Ljava/lang/Class;)V", false);
            super.checkcast(type);
        }

        @Override
        public void instanceOf(final Type type) {
            mv.visitLdcInsn(type);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                    Type.getInternalName(TraceInstanceOf.class),
                    "traceInstanceOf",
                    "(Ljava/lang/Object;Ljava/lang/Class;)Z", false);
        }
    }

    private static class TypePollutionClassVisitor extends ClassVisitor {


        private TypePollutionClassVisitor(int api, ClassWriter classWriter) {
            super(api, classWriter);
        }


        @Override
        public MethodVisitor visitMethod(int flags, String name,
                                         String desc, String signature, String[] exceptions) {
            MethodVisitor baseMethodVisitor =
                    super.visitMethod(flags, name, desc,
                            signature, exceptions);
            return new TypePollutionInstructionAdapter(api, baseMethodVisitor);
        }
    }


}


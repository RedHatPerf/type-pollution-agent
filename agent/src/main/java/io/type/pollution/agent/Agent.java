package io.type.pollution.agent;

import javassist.CannotCompileException;
import javassist.ClassPool;
import javassist.CtClass;
import javassist.expr.Cast;
import javassist.expr.ExprEditor;
import javassist.expr.Instanceof;

import java.io.IOException;
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
        if (s.startsWith("sun/")) {
            return false;
        }
        if (s.startsWith("javassist/")) {
            return false;
        }
        if (s.equals("io/type/pollution/agent/TraceInstanceOf")) {
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
                byte[] transformedClass = null;
                CtClass cl = null;
                ClassPool pool = ClassPool.getDefault();
                try {
                    cl = pool.makeClass(new java.io.ByteArrayInputStream(bytes));

                    cl.instrument(new ExprEditor() {

                        @Override
                        public void edit(final Cast c) throws CannotCompileException {
                            final String replaced = "{" +
                                    "$_ = $proceed($$);" +
                                    "io.type.pollution.agent.TraceInstanceOf.instanceOf($1, $type, true);" +
                                    "}";
                            c.replace(replaced);
                        }

                        @Override
                        public void edit(final Instanceof i) throws CannotCompileException {
                            final String replaced = "{" +
                                    "$_ = $proceed($$);" +
                                    "io.type.pollution.agent.TraceInstanceOf.instanceOf($1, $type, $_);" +
                                    "}";
                            i.replace(replaced);
                        }
                    });
                    // search all methods (including static/private) and replace instanceof bytes codes

                    // Generate changed bytecode
                    transformedClass = cl.toBytecode();

                } catch (IOException | CannotCompileException e) {
                    e.printStackTrace();
                } finally {
                    if (cl != null) {
                        cl.detach();
                    }
                }

                return transformedClass;
            }
        };
    }


}


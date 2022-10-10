package io.type.pollution.agent;


import net.bytebuddy.jar.asm.Label;
import net.bytebuddy.jar.asm.Opcodes;
import net.bytebuddy.jar.asm.Type;

public class ByteBuddyUtils {

    static class ByteBuddyTypePollutionInstructionAdapter extends net.bytebuddy.jar.asm.MethodVisitor {

        private final String classDescriptor;
        private final String methodName;

        private final String classFile;

        private String tracePrefix;

        private int line;

        protected ByteBuddyTypePollutionInstructionAdapter(int api, net.bytebuddy.jar.asm.MethodVisitor methodVisitor, String classDescriptor, String methodName, String classFile) {
            super(api, methodVisitor);
            this.classDescriptor = classDescriptor;
            this.methodName = methodName;
            this.classFile = classFile;
        }

        private String trace() {
            if (tracePrefix == null) {
                tracePrefix = classDescriptor.replace('/', '.') + "." + methodName + "(" + classFile;
            }
            return tracePrefix + ":" + line + ")";
        }

        @Override
        public void visitMethodInsn(final int opcode, final String owner, final String name, final String descriptor, final boolean isInterface) {
            if (opcode == Opcodes.INVOKEVIRTUAL && "java/lang/Class".equals(owner)) {
                switch (name) {
                    case "cast":
                        mv.visitInsn(Opcodes.DUP2);
                        mv.visitLdcInsn(trace());
                        mv.visitMethodInsn(net.bytebuddy.jar.asm.Opcodes.INVOKESTATIC,
                                Type.getInternalName(TraceInstanceOf.class),
                                "traceCast",
                                "(Ljava/lang/Class;Ljava/lang/Object;Ljava/lang/String;)V", false);
                        super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
                        break;
                    case "isInstance":
                        mv.visitLdcInsn(trace());
                        mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                                Type.getInternalName(TraceInstanceOf.class),
                                "traceIsInstance",
                                "(Ljava/lang/Class;Ljava/lang/Object;Ljava/lang/String;)Z", false);
                        break;
                    default:
                        super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
                }
            } else {
                super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
            }
        }

        @Override
        public void visitTypeInsn(final int opcode, final String type) {
            switch (opcode) {
                case Opcodes.CHECKCAST:
                    checkcast(Type.getObjectType(type));
                    break;
                case Opcodes.INSTANCEOF:
                    instanceOf(Type.getObjectType(type));
                    break;
                default:
                    super.visitTypeInsn(opcode, type);
            }
        }

        @Override
        public void visitLineNumber(final int line, final Label start) {
            this.line = line;
            super.visitLineNumber(line, start);
        }

        public void checkcast(final Type type) {
            mv.visitInsn(net.bytebuddy.jar.asm.Opcodes.DUP);
            mv.visitLdcInsn(type);
            mv.visitLdcInsn(trace());
            mv.visitMethodInsn(net.bytebuddy.jar.asm.Opcodes.INVOKESTATIC,
                    Type.getInternalName(TraceInstanceOf.class),
                    "traceCheckcast",
                    "(Ljava/lang/Object;Ljava/lang/Class;Ljava/lang/String;)V", false);
            super.visitTypeInsn(net.bytebuddy.jar.asm.Opcodes.CHECKCAST, type.getInternalName());
        }

        public void instanceOf(final Type type) {
            mv.visitLdcInsn(type);
            mv.visitLdcInsn(trace());
            mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                    Type.getInternalName(TraceInstanceOf.class),
                    "traceInstanceOf",
                    "(Ljava/lang/Object;Ljava/lang/Class;Ljava/lang/String;)Z", false);
        }
    }

    static class ByteBuddyTypePollutionClassVisitor extends net.bytebuddy.jar.asm.ClassVisitor {

        private String name;
        private String source;

        ByteBuddyTypePollutionClassVisitor(int api, net.bytebuddy.jar.asm.ClassVisitor cv) {
            super(api, cv);
        }

        @Override
        public void visitSource(final String source, final String debug) {
            this.source = source;
            super.visitSource(source, debug);
        }

        @Override
        public void visit(final int version, final int access, final String name, final String signature, final String superName, final String[] interfaces) {
            this.name = name;
            super.visit(version, access, name, signature, superName, interfaces);
        }

        @Override
        public net.bytebuddy.jar.asm.MethodVisitor visitMethod(int flags, String name,
                                                               String desc, String signature, String[] exceptions) {
            return new ByteBuddyTypePollutionInstructionAdapter(api, super.visitMethod(flags, name, desc,
                    signature, exceptions), this.name, name, source);
        }
    }
}

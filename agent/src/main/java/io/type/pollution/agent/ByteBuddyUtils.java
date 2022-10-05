package io.type.pollution.agent;


import net.bytebuddy.jar.asm.Opcodes;
import net.bytebuddy.jar.asm.Type;

public class ByteBuddyUtils {

    static class ByteBuddyTypePollutionInstructionAdapter extends net.bytebuddy.jar.asm.MethodVisitor {

        protected ByteBuddyTypePollutionInstructionAdapter(int api, net.bytebuddy.jar.asm.MethodVisitor methodVisitor) {
            super(api, methodVisitor);
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

        public void checkcast(final Type type) {
            mv.visitInsn(net.bytebuddy.jar.asm.Opcodes.DUP);
            mv.visitLdcInsn(type);
            mv.visitMethodInsn(net.bytebuddy.jar.asm.Opcodes.INVOKESTATIC,
                    Type.getInternalName(TraceInstanceOf.class),
                    "traceCheckcast",
                    "(Ljava/lang/Object;Ljava/lang/Class;)V", false);
            super.visitTypeInsn(net.bytebuddy.jar.asm.Opcodes.CHECKCAST, type.getInternalName());
        }

        public void instanceOf(final Type type) {
            mv.visitLdcInsn(type);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                    Type.getInternalName(TraceInstanceOf.class),
                    "traceInstanceOf",
                    "(Ljava/lang/Object;Ljava/lang/Class;)Z", false);
        }
    }

    static class ByteBuddyTypePollutionClassVisitor extends net.bytebuddy.jar.asm.ClassVisitor {

        ByteBuddyTypePollutionClassVisitor(int api, net.bytebuddy.jar.asm.ClassVisitor cv) {
            super(api, cv);
        }

        @Override
        public net.bytebuddy.jar.asm.MethodVisitor visitMethod(int flags, String name,
                                                               String desc, String signature, String[] exceptions) {
            return new ByteBuddyTypePollutionInstructionAdapter(api, super.visitMethod(flags, name, desc,
                    signature, exceptions));
        }
    }
}

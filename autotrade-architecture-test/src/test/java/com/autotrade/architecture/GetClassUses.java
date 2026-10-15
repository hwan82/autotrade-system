package com.autotrade.architecture;

import com.tngtech.archunit.thirdparty.org.objectweb.asm.ClassReader;
import com.tngtech.archunit.thirdparty.org.objectweb.asm.ClassVisitor;
import com.tngtech.archunit.thirdparty.org.objectweb.asm.Handle;
import com.tngtech.archunit.thirdparty.org.objectweb.asm.Label;
import com.tngtech.archunit.thirdparty.org.objectweb.asm.MethodVisitor;
import com.tngtech.archunit.thirdparty.org.objectweb.asm.Opcodes;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * {@code getClass()}는 바로 이름을 읽는 데만 쓴다(architect PR #108 재검토 AR-9). javac는 받는 쪽 정적 타입과 무관하게
 * {@code Object.getClass}를 부르므로, 클래스 객체의 {@code getClass()}({@code String.class.getClass()})로 {@code Class.class}
 * 리터럴 없이 {@code Class} 타입 객체를 얻어 Jackson에 넘기면 이름 문자열로 클래스를 얻는다. ArchUnit은 명령 순서를 주지
 * 않으므로 바이트코드를 직접 읽어, {@code Object.getClass} 바로 다음 명령이 {@code Class.getSimpleName()}이나
 * {@code Class.getName()}이 아닌 곳과 {@code Object::getClass} 메서드 참조를 찾는다.
 */
final class GetClassUses {

    static final Set<String> NAME_READERS = Set.of("getSimpleName", "getName");

    private GetClassUses() {}

    /** 이름 읽기가 아닌 {@code getClass()} 사용 위치(메서드 이름과 줄). */
    static List<String> of(URI classFile) {
        try (InputStream in = classFile.toURL().openStream()) {
            return of(in.readAllBytes());
        } catch (IOException e) {
            throw new UncheckedIOException("클래스 파일을 읽지 못했다: " + classFile, e);
        }
    }

    static List<String> of(byte[] bytes) {
        List<String> out = new ArrayList<>();
        ClassReader reader = new ClassReader(bytes);
        String owner = reader.getClassName().replace('/', '.');
        reader.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                return new Scanner(owner + "." + name, out);
            }
        }, 0);
        return out;
    }

    private static final class Scanner extends MethodVisitor {
        private final String method;
        private final List<String> out;
        private int line;
        private int pendingLine = -1;

        Scanner(String method, List<String> out) {
            super(Opcodes.ASM9);
            this.method = method;
            this.out = out;
        }

        @Override
        public void visitLineNumber(int line, Label start) {
            this.line = line;
        }

        /** 다음 명령이 이름 읽기가 아니면 앞의 getClass()를 위반으로 적는다. */
        private void instruction(boolean nameReader) {
            if (pendingLine >= 0 && !nameReader) {
                out.add(method + "의 getClass() 결과를 이름 읽기 밖에서 쓴다 (줄 " + pendingLine + ")");
            }
            pendingLine = -1;
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
            instruction(owner.equals("java/lang/Class") && NAME_READERS.contains(name)
                    && descriptor.equals("()Ljava/lang/String;"));
            if (name.equals("getClass") && descriptor.equals("()Ljava/lang/Class;")) {
                pendingLine = line;
            }
        }

        @Override
        public void visitInvokeDynamicInsn(String name, String descriptor, Handle bsm, Object... args) {
            instruction(false);
            for (Object a : args) {
                if (a instanceof Handle h && h.getName().equals("getClass") && h.getDesc().equals("()Ljava/lang/Class;")) {
                    out.add(method + "가 getClass 메서드 참조를 만든다 (줄 " + line + ")");
                }
            }
        }

        @Override public void visitInsn(int opcode) { instruction(false); }
        @Override public void visitIntInsn(int opcode, int operand) { instruction(false); }
        @Override public void visitVarInsn(int opcode, int varIndex) { instruction(false); }
        @Override public void visitTypeInsn(int opcode, String type) { instruction(false); }
        @Override public void visitFieldInsn(int opcode, String owner, String name, String descriptor) { instruction(false); }
        @Override public void visitJumpInsn(int opcode, Label label) { instruction(false); }
        @Override public void visitLdcInsn(Object value) { instruction(false); }
        @Override public void visitIincInsn(int varIndex, int increment) { instruction(false); }
        @Override public void visitTableSwitchInsn(int min, int max, Label dflt, Label... labels) { instruction(false); }
        @Override public void visitLookupSwitchInsn(Label dflt, int[] keys, Label[] labels) { instruction(false); }
        @Override public void visitMultiANewArrayInsn(String descriptor, int numDimensions) { instruction(false); }

        @Override
        public void visitEnd() {
            instruction(false);
        }
    }
}

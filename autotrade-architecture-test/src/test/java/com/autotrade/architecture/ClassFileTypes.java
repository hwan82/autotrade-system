package com.autotrade.architecture;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 클래스 파일의 상수 풀에 이름이 나오는 타입 전부(독립 QA PR #108 F2). ArchUnit의 의존 목록은 형변환({@code checkcast}) 대상과
 * 부른 메서드의 매개변수·반환 타입을 넣지 않으므로, 허용 목록 검사는 상수 풀을 직접 읽어 빈틈을 없앤다. 클래스 상수(호출 대상,
 * 형변환, {@code instanceof}, {@code new}, 클래스 객체)와 서술자·제네릭 서명(필드·메서드·호출 대상의 매개변수·반환 타입, 주석)에
 * 나오는 타입을 모은다. 문자열 상수({@code CONSTANT_String}이 가리키는 값)는 코드가 아니라 데이터이므로 뺀다.
 */
final class ClassFileTypes {

    private static final Pattern DESCRIPTOR_TYPE = Pattern.compile("L([\\w/$]+)[;<]");

    private ClassFileTypes() {}

    /** 클래스 파일에 이름이 나오는 타입(점 표기, 배열은 원소 타입). */
    static Set<String> of(URI classFile) {
        try (InputStream in = classFile.toURL().openStream()) {
            return of(in.readAllBytes());
        } catch (IOException e) {
            throw new UncheckedIOException("클래스 파일을 읽지 못했다: " + classFile, e);
        }
    }

    static Set<String> of(byte[] bytes) {
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
            if (in.readInt() != 0xCAFEBABE) {
                throw new IllegalArgumentException("클래스 파일이 아니다");
            }
            in.readUnsignedShort();
            in.readUnsignedShort();
            int count = in.readUnsignedShort();
            String[] utf8 = new String[count];
            Set<Integer> classNames = new HashSet<>();
            Set<Integer> stringValues = new HashSet<>();
            for (int i = 1; i < count; i++) {
                int tag = in.readUnsignedByte();
                switch (tag) {
                    case 1 -> utf8[i] = in.readUTF();
                    case 7 -> classNames.add(in.readUnsignedShort());
                    case 8 -> stringValues.add(in.readUnsignedShort());
                    case 16, 19, 20 -> in.readUnsignedShort(); // MethodType(서술자는 utf8로 따로 잡힌다), Module, Package
                    case 15 -> { in.readUnsignedByte(); in.readUnsignedShort(); }
                    case 3, 4 -> in.readInt();
                    case 9, 10, 11, 12, 17, 18 -> in.readInt();
                    case 5, 6 -> { in.readLong(); i++; }
                    default -> throw new IllegalArgumentException("알 수 없는 상수 태그 " + tag);
                }
            }
            Set<String> types = new TreeSet<>();
            for (int i = 1; i < count; i++) {
                if (utf8[i] == null || stringValues.contains(i) && !classNames.contains(i)) {
                    continue;
                }
                if (classNames.contains(i)) {
                    String n = utf8[i];
                    if (n.startsWith("[")) {
                        addDescriptorTypes(n, types);
                    } else {
                        types.add(n.replace('/', '.'));
                    }
                } else {
                    addDescriptorTypes(utf8[i], types);
                }
            }
            return types;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void addDescriptorTypes(String value, Set<String> types) {
        Matcher m = DESCRIPTOR_TYPE.matcher(value);
        while (m.find()) {
            types.add(m.group(1).replace('/', '.'));
        }
    }
}

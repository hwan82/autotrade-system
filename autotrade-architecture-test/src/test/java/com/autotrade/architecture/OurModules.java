package com.autotrade.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.core.importer.Location;
import com.tngtech.archunit.core.importer.Locations;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.jar.JarFile;
import java.util.stream.Stream;

/**
 * 아키텍처 테스트가 "우리 모듈"을 알아내는 방법. Gradle이 넘긴 프로젝트 배치표(시스템 속성 {@value #PROJECT_LAYOUT_PROPERTY},
 * {@code autotrade-architecture-test/build.gradle.kts})로 클래스패스 항목과 클래스 위치를 Gradle 프로젝트에 매핑한다.
 *
 * <p>배치표: 프로젝트마다 폴더({@code projectDir})와 <b>선언된 운영 출력</b>(main 클래스 폴더, main 자원 폴더, jar 산출물).
 * 이 모듈은 테스트 출력도 담는다. 항목 → 프로젝트는 폴더 접두어가 아니라 선언 출력과 <b>정확히 일치</b>로 정한다.
 * <ul>
 *   <li>폴더 이름이 아니라 프로젝트 경로를 쓰는 이유: Gradle은 부모가 다르면 같은 이름의 프로젝트를 허용한다
 *       ({@code :autotrade-risk}와 {@code :tools:autotrade-risk}, 독립 QA PR #21 Q-2).</li>
 *   <li>폴더 접두어가 아니라 선언 출력을 쓰는 이유: 한 프로젝트의 출력 경로를 다른 프로젝트 폴더 안으로 돌리면 접두어
 *       매핑은 그 클래스를 다른 프로젝트 것으로 판정한다(risk-ops PR #21 재검토 2 R-7, M7 실측).</li>
 *   <li>운영·테스트 구분도 경로 모양({@code build/classes/test} 같은 패턴)이 아니라 선언 종류로 한다. 운영 출력을
 *       테스트처럼 보이는 경로로 돌려도 운영으로 검사된다.</li>
 * </ul>
 *
 * <p>속성이 없으면(Gradle 밖, IDE 자체 실행기) 예외를 던져 닫힌 쪽으로 실패한다.
 */
final class OurModules {

    static final String PROJECT_LAYOUT_PROPERTY = "autotrade.projectLayout";
    static final String SELF = ":autotrade-architecture-test";

    private static final ImportOption EXTERNAL_PRODUCTION_ONLY = new ImportOption.DoNotIncludeTests();

    private OurModules() {}

    /** MAIN·TEST: 클래스패스 매핑에 쓰는 선언 출력. BUILT: 컴파일·자원·묶음 태스크의 실제 출력(폴더 위치 검사에만 쓴다). */
    enum Kind { MAIN, TEST, BUILT }

    /** 프로젝트가 선언한 출력 하나(정규화한 파일 경로). */
    record Output(String project, Kind kind, String file) {}

    /** 프로젝트 경로 → 폴더, 선언 출력 목록. */
    record Layout(Map<String, String> dirs, List<Output> outputs, Optional<String> root, List<String> externalRoots) {

        Layout(Map<String, String> dirs, List<Output> outputs) {
            this(dirs, outputs, Optional.empty(), List.of());
        }
    }

    /** 클래스패스 항목 하나. {@code kind}가 비어 있으면 우리 프로젝트 폴더 안이지만 어느 선언 출력과도 맞지 않는 항목이다. */
    record Entry(String project, Optional<Kind> kind, Path path) {}

    /** 시스템 속성의 배치표. */
    static Layout layout() {
        String property = System.getProperty(PROJECT_LAYOUT_PROPERTY);
        if (property == null || property.isBlank()) {
            throw new IllegalStateException("시스템 속성 " + PROJECT_LAYOUT_PROPERTY
                    + "가 없다. Gradle(./gradlew :autotrade-architecture-test:test)로 실행해야 한다");
        }
        return parseLayout(property);
    }

    /** 시스템 속성의 프로젝트 경로 → 폴더 표(이 모듈 포함). */
    static Map<String, String> projectDirs() {
        return layout().dirs();
    }

    /** {@code "DIR\t:a\t/repo/a|MAIN\t:a\t/repo/a/build/libs/a.jar|..."} 형식. 같은 프로젝트의 폴더가 두 번이면 예외. */
    static Layout parseLayout(String property) {
        Map<String, String> dirs = new TreeMap<>();
        List<Output> outputs = new ArrayList<>();
        String root = null;
        List<String> externalRoots = new ArrayList<>();
        for (String item : property.split("\\|")) {
            String[] f = item.split("\t", -1);
            if (f.length != 3 || f[1].isEmpty() || f[2].isEmpty()) {
                throw new IllegalArgumentException("배치표 항목 형식이 틀렸다: " + item);
            }
            switch (f[0]) {
                case "DIR" -> {
                    if (dirs.put(f[1], normalize(f[2])) != null) {
                        throw new IllegalArgumentException("같은 프로젝트 경로가 두 번 있다: " + f[1]);
                    }
                }
                case "MAIN" -> outputs.add(new Output(f[1], Kind.MAIN, normalize(f[2])));
                case "TEST" -> outputs.add(new Output(f[1], Kind.TEST, normalize(f[2])));
                case "BUILT" -> outputs.add(new Output(f[1], Kind.BUILT, normalize(f[2])));
                case "ROOT" -> root = normalize(f[2]);
                case "EXTERNAL" -> externalRoots.add(normalize(f[2]));
                default -> throw new IllegalArgumentException("배치표 항목 종류를 모른다: " + item);
            }
        }
        for (Output o : outputs) {
            if (!dirs.containsKey(o.project())) {
                throw new IllegalArgumentException("폴더가 없는 프로젝트의 출력: " + o);
            }
        }
        return new Layout(dirs, List.copyOf(outputs), Optional.ofNullable(root), List.copyOf(externalRoots));
    }

    /** {@code ":a=/repo/a|:b:c=/repo/b/c"} 형식의 폴더 표(단위 테스트용). 같은 경로가 두 번 나오면 예외. */
    static Map<String, String> parse(String property) {
        Map<String, String> out = new TreeMap<>();
        for (String item : property.split("\\|")) {
            int eq = item.indexOf('=');
            if (eq <= 0 || eq == item.length() - 1) {
                throw new IllegalArgumentException("프로젝트 표 항목 형식이 틀렸다: " + item);
            }
            if (out.put(item.substring(0, eq), normalize(item.substring(eq + 1))) != null) {
                throw new IllegalArgumentException("같은 프로젝트 경로가 두 번 있다: " + item.substring(0, eq));
            }
        }
        return out;
    }

    /** 파일 경로를 담은 가장 안쪽 프로젝트 폴더의 프로젝트. 폴더 경계를 지킨다. 선언 출력 확인 전 "우리 폴더 안인가" 판단용. */
    static Optional<String> projectOf(String file, Map<String, String> dirs) {
        String f = normalize(file) + "/";
        String best = null;
        int bestLength = -1;
        for (Map.Entry<String, String> e : dirs.entrySet()) {
            String dir = e.getValue() + "/";
            if (f.startsWith(dir) && dir.length() > bestLength) {
                best = e.getKey();
                bestLength = dir.length();
            }
        }
        return Optional.ofNullable(best);
    }

    /** 클래스패스 항목과 정확히 같은 선언 출력. */
    static Optional<Output> outputOf(String entry, Layout layout) {
        String f = normalize(entry);
        return layout.outputs().stream().filter(o -> o.kind() != Kind.BUILT && o.file().equals(f)).findFirst();
    }

    /**
     * 클래스 위치(파일)를 담은 선언 출력: jar는 jar 파일 경로가 정확히 같아야 하고, 폴더 출력은 그 폴더 안이면 된다.
     * 여럿이면 가장 긴 경로.
     */
    static Optional<Output> containingOutput(String file, Layout layout) {
        String f = normalize(file);
        Output best = null;
        for (Output o : layout.outputs()) {
            if (o.kind() == Kind.BUILT) {
                continue;
            }
            boolean inside = f.equals(o.file()) || (!o.file().endsWith(".jar") && f.startsWith(o.file() + "/"));
            if (inside && (best == null || o.file().length() > best.file().length())) {
                best = o;
            }
        }
        return Optional.ofNullable(best);
    }

    /**
     * 배치표 자체의 문제: 선언 출력이 자기 프로젝트 폴더 밖이거나 다른(더 안쪽) 프로젝트 폴더 안에 있음, 같은 출력을 두
     * 프로젝트가 선언함. 출력 경로를 다른 모듈 폴더로 돌리는 위장(R-7 M7)을 여기서 잡는다.
     */
    static List<String> layoutProblems(Layout layout) {
        List<String> out = new ArrayList<>();
        Map<String, String> declaredBy = new TreeMap<>();
        for (Output o : layout.outputs()) {
            Optional<String> home = projectOf(o.file(), layout.dirs());
            if (home.isEmpty() || !home.get().equals(o.project())) {
                out.add("선언 출력이 자기 프로젝트 폴더 밖: " + o.project() + " " + o.kind() + " " + o.file()
                        + " (담은 프로젝트: " + home.orElse("없음") + ")");
            }
            String previous = declaredBy.putIfAbsent(o.file(), o.project());
            if (previous != null && !previous.equals(o.project())) {
                out.add("같은 출력을 두 프로젝트가 선언: " + o.file() + " (" + previous + ", " + o.project() + ")");
            }
        }
        out.sort(null);
        return out;
    }

    /** 프로젝트 경로의 마지막 이름({@code :tools:autotrade-risk} → {@code autotrade-risk}). */
    static String nameOf(String projectPath) {
        return projectPath.substring(projectPath.lastIndexOf(':') + 1);
    }

    /** ArchUnit 위치 URI({@code file:...} 또는 {@code jar:file:...!/...})의 파일 시스템 경로. */
    static String fileOf(URI location) {
        URI uri = location;
        if ("jar".equals(uri.getScheme())) {
            String s = uri.getRawSchemeSpecificPart();
            int bang = s.indexOf("!/");
            uri = URI.create(bang >= 0 ? s.substring(0, bang) : s);
        }
        return Paths.get(uri).toAbsolutePath().toString();
    }

    /** 테스트 JVM 클래스패스에서 우리 프로젝트 폴더 안의 항목(존재하는 것만, 경로 순). */
    static List<Entry> classpathEntries(Layout layout) {
        return classpathEntries(layout, System.getProperty("java.class.path").split(File.pathSeparator));
    }

    static List<Entry> classpathEntries(Layout layout, String... classpath) {
        List<Entry> out = new ArrayList<>();
        for (String raw : classpath) {
            if (raw.isBlank()) {
                continue;
            }
            Path path = Paths.get(raw).toAbsolutePath();
            if (!Files.exists(path)) {
                continue;
            }
            Optional<Output> declared = outputOf(path.toString(), layout);
            if (declared.isPresent()) {
                out.add(new Entry(declared.get().project(), Optional.of(declared.get().kind()), path));
            } else {
                projectOf(path.toString(), layout.dirs())
                        .ifPresent(p -> out.add(new Entry(p, Optional.empty(), path)));
            }
        }
        out.sort(Comparator.comparing((Entry e) -> e.path().toString()));
        return out;
    }

    /** 우리 프로젝트 폴더 안인데 선언 출력과 맞지 않는 클래스패스 항목. 있으면 실패다. */
    static List<String> undeclaredEntries(List<Entry> entries) {
        return entries.stream()
                .filter(e -> e.kind().isEmpty())
                .map(e -> "선언 출력과 맞지 않는 클래스패스 항목: " + e.path() + " (폴더 기준 " + e.project() + ")")
                .sorted()
                .toList();
    }

    /**
     * 클래스패스 항목은 선언 출력(MAIN·TEST)이거나 외부 허용 위치(Gradle 의존성 캐시, Gradle 배포판) 안이어야 한다. 저장소나
     * 우리 프로젝트 폴더 안인데 선언 출력이 아닌 항목(선언하지 않은 출력, 저장소 안 파일 jar), 허용 위치 밖 항목(임의 경로
     * jar)은 위반이다. 없는 경로는 JVM이 읽지 못하므로 건너뛴다.
     */
    static List<String> unexpectedClasspathEntries(Layout layout, String... classpath) {
        List<String> out = new ArrayList<>();
        for (String raw : classpath) {
            if (raw.isBlank()) {
                continue;
            }
            Path path = Paths.get(raw).toAbsolutePath();
            if (!Files.exists(path) || outputOf(path.toString(), layout).isPresent()) {
                continue;
            }
            String f = normalize(path.toString()) + "/";
            boolean inRepo = projectOf(path.toString(), layout.dirs()).isPresent()
                    || layout.root().map(r -> f.startsWith(r + "/")).orElse(false);
            boolean external = layout.externalRoots().stream().anyMatch(r -> f.startsWith(r + "/"));
            if (inRepo) {
                out.add("저장소 안인데 선언 출력이 아닌 클래스패스 항목: " + path);
            } else if (!external) {
                out.add("허용 위치(Gradle 의존성 캐시·배포판) 밖 클래스패스 항목: " + path);
            }
        }
        out.sort(null);
        return out;
    }

    static List<String> unexpectedClasspathEntries(Layout layout) {
        return unexpectedClasspathEntries(layout, System.getProperty("java.class.path").split(File.pathSeparator));
    }

    /** 운영 출력 항목(선언 종류 MAIN)으로 클래스를 담을 수 있는 것: 폴더나 jar. */
    static List<Entry> productionEntries(Layout layout) {
        return classpathEntries(layout).stream()
                .filter(e -> e.kind().equals(Optional.of(Kind.MAIN)))
                .filter(e -> isClassContainer(e.path()))
                .toList();
    }

    /** 항목(폴더나 jar) 전체를 패키지 필터 없이 가져온다. 그 밖의 파일은 빈 결과. */
    static JavaClasses importEntry(Path entry, ImportOption... options) {
        ClassFileImporter importer = new ClassFileImporter();
        for (ImportOption option : options) {
            importer = importer.withImportOption(option);
        }
        if (isClassContainer(entry)) {
            return importer.importLocations(Set.of(locationOf(entry)));
        }
        return importer.importClasses();
    }

    /** 폴더(하위 전체)나 jar 안의 {@code .class} 파일 수. 없는 경로는 0. */
    static long classFileCount(Path path) {
        try {
            if (Files.isDirectory(path)) {
                try (Stream<Path> files = Files.walk(path)) {
                    return files.filter(p -> p.getFileName().toString().endsWith(".class")).count();
                }
            }
            if (Files.isRegularFile(path) && path.getFileName().toString().endsWith(".jar")) {
                try (JarFile jar = new JarFile(path.toFile())) {
                    return jar.stream().filter(e -> e.getName().endsWith(".class")).count();
                }
            }
            return 0;
        } catch (IOException e) {
            throw new UncheckedIOException("출력을 읽을 수 없다: " + path, e);
        }
    }

    /**
     * {@link ArchitectureTest}의 검사 범위: 우리 프로젝트 운영 항목 전체(패키지 필터 없음, 독립 QA PR #21 Q-1)와 예전 범위인
     * 클래스패스의 {@code com.autotrade} 위치 전체(외부 jar 포함)의 합. 예전 범위보다 좁아지지 않는다. 어떤 클래스를
     * 넣을지는 {@link #architectureImportOption}이 정한다.
     */
    static Set<Location> architectureScope() {
        Set<Location> out = new LinkedHashSet<>(Locations.ofPackage("com.autotrade"));
        for (Entry e : productionEntries(layout())) {
            out.add(locationOf(e.path()));
        }
        return out;
    }

    /**
     * {@link ArchitectureTest} 범위의 운영 클래스 판정. 우리 프로젝트 폴더 안이면 선언 종류로 정한다(이 모듈의 테스트 출력만
     * 뺀다, 선언되지 않은 항목은 넣는다: 넓게 보고 소유 테스트가 따로 실패시킨다). 우리 폴더 밖(외부 jar)은 예전과 같이
     * 경로 패턴({@link ImportOption.DoNotIncludeTests})으로 정한다.
     */
    static ImportOption architectureImportOption() {
        Layout layout = layout();
        return location -> {
            String file = fileOf(location.asURI());
            Optional<Output> output = containingOutput(file, layout);
            if (output.isPresent()) {
                return output.get().kind() == Kind.MAIN;
            }
            if (projectOf(file, layout.dirs()).isPresent()) {
                return true;
            }
            return EXTERNAL_PRODUCTION_ONLY.includes(location);
        };
    }

    /** JVM이 클래스를 읽을 수 있는 클래스패스 항목: 폴더나 jar. */
    static boolean isClassContainer(Path path) {
        return Files.isDirectory(path) || path.getFileName().toString().endsWith(".jar");
    }

    static Location locationOf(Path path) {
        if (Files.isDirectory(path)) {
            return Location.of(path);
        }
        try (JarFile jar = new JarFile(path.toFile())) {
            return Location.of(jar);
        } catch (IOException e) {
            throw new UncheckedIOException("클래스패스 항목을 열 수 없다: " + path, e);
        }
    }

    static String normalize(String path) {
        String p = path.replace('\\', '/');
        while (p.length() > 1 && p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        return p;
    }
}

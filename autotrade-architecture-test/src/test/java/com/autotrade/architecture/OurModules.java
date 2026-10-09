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

/**
 * 아키텍처 테스트가 "우리 모듈"을 알아내는 방법. Gradle이 넘긴 프로젝트 경로 → 폴더 표(시스템 속성
 * {@value #PROJECT_DIRS_PROPERTY}, {@code autotrade-architecture-test/build.gradle.kts})로 클래스패스 항목과 클래스 위치를
 * Gradle 프로젝트에 매핑한다.
 *
 * <p>폴더 이름이 아니라 프로젝트 경로를 쓰는 이유: Gradle은 부모가 다르면 같은 이름의 프로젝트를 허용한다
 * ({@code :autotrade-risk}와 {@code :tools:autotrade-risk}). 폴더 이름으로 모듈을 정하면 중첩 프로젝트가 진짜 모듈로
 * 인정된다(독립 QA PR #21 Q-2, E5 실측).
 *
 * <p>속성이 없으면(Gradle 밖, IDE 자체 실행기) 예외를 던져 닫힌 쪽으로 실패한다.
 */
final class OurModules {

    static final String PROJECT_DIRS_PROPERTY = "autotrade.projectDirs";
    static final String SELF = ":autotrade-architecture-test";

    private static final ImportOption PRODUCTION_ONLY = new ImportOption.DoNotIncludeTests();

    private OurModules() {}

    /** 클래스패스 항목 하나와 그 항목을 만든 프로젝트. */
    record Entry(String project, Path path) {}

    /** 시스템 속성의 프로젝트 경로 → 폴더(정규화) 표. 이 모듈 자신 포함. */
    static Map<String, String> projectDirs() {
        String property = System.getProperty(PROJECT_DIRS_PROPERTY);
        if (property == null || property.isBlank()) {
            throw new IllegalStateException("시스템 속성 " + PROJECT_DIRS_PROPERTY
                    + "가 없다. Gradle(./gradlew :autotrade-architecture-test:test)로 실행해야 한다");
        }
        return parse(property);
    }

    /** {@code ":a=/repo/a|:b:c=/repo/b/c"} 형식. 같은 경로가 두 번 나오면 예외. */
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

    /** 파일 경로를 만든 프로젝트. 가장 긴(가장 안쪽) 프로젝트 폴더가 이기고, 폴더 경계를 지킨다. */
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

    /** 테스트 JVM 클래스패스에서 우리 프로젝트가 만든 항목(존재하는 것만, 경로 순). */
    static List<Entry> classpathEntries(Map<String, String> dirs) {
        List<Entry> out = new ArrayList<>();
        for (String raw : System.getProperty("java.class.path").split(File.pathSeparator)) {
            if (raw.isBlank()) {
                continue;
            }
            Path path = Paths.get(raw).toAbsolutePath();
            if (!Files.exists(path)) {
                continue;
            }
            projectOf(path.toString(), dirs).ifPresent(p -> out.add(new Entry(p, path)));
        }
        out.sort(Comparator.comparing((Entry e) -> e.path().toString()));
        return out;
    }

    /** 운영 산출물 항목(테스트 출력 제외). */
    static List<Entry> productionEntries(Map<String, String> dirs) {
        return classpathEntries(dirs).stream()
                .filter(e -> isClassContainer(e.path()))
                .filter(e -> PRODUCTION_ONLY.includes(locationOf(e.path())))
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

    /**
     * {@link ArchitectureTest}의 검사 범위: 우리 프로젝트 운영 항목 전체(패키지 필터 없음, 독립 QA PR #21 Q-1)와 예전 범위인
     * 클래스패스의 {@code com.autotrade} 위치 전체(외부 jar 포함)의 합. 예전 범위보다 좁아지지 않는다.
     */
    static Set<Location> architectureScope() {
        Set<Location> out = new LinkedHashSet<>(Locations.ofPackage("com.autotrade"));
        for (Entry e : productionEntries(projectDirs())) {
            out.add(locationOf(e.path()));
        }
        return out;
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

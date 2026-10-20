package com.autotrade.app.collector;

import com.autotrade.marketdata.daily.Csv;
import com.autotrade.marketdata.daily.SnapshotManifest;
import com.autotrade.marketdata.recording.Sha256;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 스냅샷 폴더 쓰기(D-019 계약, ADR-008 2.4). 임시 폴더에 CSV를 한 줄씩 쓰고, 다 쓰면 파일별 SHA-256·행 수로 manifest를
 * 만든 뒤 폴더째 원자적으로 옮긴다. 같은 ID 폴더가 이미 있으면 덮어쓰지 않는다(ID에 내용 해시가 들어 있어 같은 ID = 같은 내용).
 * 줄바꿈은 {@code \n}, 인코딩 UTF-8(BOM 없음)로 고정해 같은 입력이면 같은 해시가 나온다.
 */
public final class SnapshotWriter implements AutoCloseable {

    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private final Path root;
    private final Path tmp;
    private final Map<String, Sink> sinks = new LinkedHashMap<>();
    private boolean committed;

    public SnapshotWriter(Path root, String prefix) throws IOException {
        this.root = root;
        Files.createDirectories(root);
        this.tmp = Files.createTempDirectory(root, ".tmp-" + prefix + "-");
    }

    /** CSV 파일 하나를 연다. 헤더를 바로 쓴다. */
    public Sink open(String name, List<String> header) throws IOException {
        if (sinks.containsKey(name)) {
            throw new IllegalStateException("이미 연 파일입니다: " + name);
        }
        Sink s = new Sink(Files.newBufferedWriter(tmp.resolve(name), StandardCharsets.UTF_8));
        s.out.write(Csv.join(header));
        s.out.write('\n');
        sinks.put(name, s);
        return s;
    }

    /**
     * 파일을 닫고 manifest를 쓴 뒤 {@code root/<id>}로 옮긴다. ID는 {@code <prefix>-<idDate>-<hashFile SHA-256 앞 8자리>}.
     *
     * @return 최종 폴더(이미 있었으면 그 폴더)
     */
    public Path commit(String prefix, String idDate, String hashFile, String createdAt, String scriptVersion,
            List<SnapshotManifest.Source> sources, SnapshotManifest.Coverage coverage) throws IOException {
        return commit(prefix, idDate, List.of(hashFile), createdAt, scriptVersion, sources, coverage);
    }

    /**
     * 해시 기준 파일이 여럿인 스냅샷(공시 스냅샷: 행 파일과 유형별 수집 구간 파일, D-122). 하나면 그 파일의 SHA-256, 여럿이면
     * {@code <이름>:<SHA-256>\n} 줄들(주어진 순서)의 SHA-256으로 ID를 만든다. 그래서 행이 같아도 수집 구간이 다르면 ID가 다르다.
     */
    public Path commit(String prefix, String idDate, List<String> hashFiles, String createdAt, String scriptVersion,
            List<SnapshotManifest.Source> sources, SnapshotManifest.Coverage coverage) throws IOException {
        List<SnapshotManifest.FileEntry> files = new ArrayList<>();
        for (Map.Entry<String, Sink> e : sinks.entrySet()) {
            e.getValue().out.close();
            files.add(new SnapshotManifest.FileEntry(e.getKey(), Sha256.ofFile(tmp.resolve(e.getKey())), e.getValue().rows));
        }
        StringBuilder joined = new StringBuilder();
        String sha = null;
        for (String hashFile : hashFiles) {
            sha = files.stream().filter(f -> f.name().equals(hashFile)).findFirst()
                    .orElseThrow(() -> new IllegalStateException("해시 기준 파일이 없습니다: " + hashFile)).sha256();
            joined.append(hashFile).append(':').append(sha).append('\n');
        }
        if (hashFiles.size() != 1) {
            sha = sha256(joined.toString());
        }
        String id = prefix + "-" + idDate + "-" + sha.substring(0, 8).toLowerCase(java.util.Locale.ROOT);
        SnapshotManifest m = new SnapshotManifest(id, SnapshotManifest.SCHEMA_VERSION, createdAt, scriptVersion, sources, files,
                coverage);
        JSON.writeValue(tmp.resolve(SnapshotManifest.FILE_NAME).toFile(), m);
        Path target = root.resolve(id);
        committed = true;
        if (Files.exists(target)) {
            deleteTmp();
            return target;
        }
        Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
        return target;
    }

    @Override
    public void close() throws IOException {
        if (!committed) {
            for (Sink s : sinks.values()) {
                s.out.close();
            }
            deleteTmp();
        }
    }

    private void deleteTmp() throws IOException {
        if (!Files.exists(tmp)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(tmp)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        }
    }

    private static String sha256(String text) {
        try {
            return java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256을 쓸 수 없습니다", e);
        }
    }

    /** CSV 한 파일. */
    public static final class Sink {
        private final BufferedWriter out;
        private long rows;

        private Sink(BufferedWriter out) {
            this.out = out;
        }

        public void write(List<String> row) throws IOException {
            out.write(Csv.join(row));
            out.write('\n');
            rows++;
        }

        public long rows() {
            return rows;
        }
    }
}

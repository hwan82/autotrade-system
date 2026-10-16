package com.autotrade.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileKakaoTokenStoreTest {

    private static final Instant T = Instant.parse("2026-10-16T00:00:00Z");

    @TempDir
    Path dir;

    private Path file() {
        return dir.resolve(FileKakaoTokenStore.DEFAULT_FILE);
    }

    @Test
    void 남긴_토큰을_같은_출처의_다른_저장소가_읽는다() throws Exception {
        new FileKakaoTokenStore(file(), "REFRESH-CONFIG").save(new KakaoToken("ACCESS-1", "REFRESH-2", T));
        // 다른 프로세스(같은 secrets.json)
        FileKakaoTokenStore other = new FileKakaoTokenStore(file(), "REFRESH-CONFIG");
        assertThat(other.latest()).hasValueSatisfying(t -> {
            assertThat(t.accessToken()).isEqualTo("ACCESS-1");
            assertThat(t.refreshToken()).isEqualTo("REFRESH-2");
            assertThat(t.accessExpiresAt()).isEqualTo(T);
        });
        assertThat(other.initial(new KakaoToken("unset", "REFRESH-CONFIG", Instant.EPOCH)).refreshToken())
                .isEqualTo("REFRESH-2");
        // 파일에는 설정 리프레시 토큰 값이 아니라 해시만 있다. 임시 파일은 남지 않는다
        String text = Files.readString(file(), StandardCharsets.UTF_8);
        assertThat(text).doesNotContain("REFRESH-CONFIG").contains("\"source\"");
        try (var files = Files.list(file().getParent())) {
            assertThat(files.map(p -> p.getFileName().toString())).containsExactly("kakao-token.json");
        }
    }

    @Test
    void 사용자가_새_리프레시_토큰을_넣으면_옛_파일을_쓰지_않는다() throws Exception {
        new FileKakaoTokenStore(file(), "REFRESH-OLD-CONSENT").save(new KakaoToken("ACCESS-1", "REFRESH-2", T));
        FileKakaoTokenStore fresh = new FileKakaoTokenStore(file(), "REFRESH-NEW-CONSENT");
        assertThat(fresh.latest()).isEmpty();
        KakaoToken fromConfig = new KakaoToken("unset", "REFRESH-NEW-CONSENT", Instant.EPOCH);
        assertThat(fresh.initial(fromConfig)).isSameAs(fromConfig);
    }

    @Test
    void 없거나_깨졌거나_너무_큰_파일은_빈_값이다() throws Exception {
        FileKakaoTokenStore store = new FileKakaoTokenStore(file(), "R");
        assertThat(store.latest()).isEmpty();
        Files.createDirectories(file().getParent());
        Files.writeString(file(), "not json");
        assertThat(store.latest()).isEmpty();
        store.save(new KakaoToken("A", "R2", T));
        String good = Files.readString(file());
        // 상한을 빼면 정상으로 읽힐 내용: 앞은 올바른 JSON이고 뒤에 공백만 붙였다
        Files.writeString(file(), good + " ".repeat(FileKakaoTokenStore.MAX_BYTES));
        assertThat(store.latest()).isEmpty();
        Files.writeString(file(), good.replace("\"access-expires-at\"", "\"x\""));
        assertThat(store.latest()).isEmpty();
    }

    @Test
    void 문자열에_값이_나오지_않는다() {
        assertThat(new FileKakaoTokenStore(file(), "REFRESH-CONFIG").toString()).doesNotContain("REFRESH");
    }
}

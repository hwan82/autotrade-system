package com.autotrade.app.collector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CollectorSecretsTest {

    @TempDir
    Path dir;

    @Test
    void 파일이_없으면_빈_값이다() {
        CollectorSecrets s = CollectorSecrets.load(dir);
        assertThat(s.krxAuthKey().isPresent()).isFalse();
        assertThat(s.openDartKey().isPresent()).isFalse();
    }

    @Test
    void 두_키를_읽고_문자열로_바뀌어도_가린다() throws IOException {
        Files.writeString(dir.resolve("collector-secrets.json"),
                "{\"krx\":{\"auth-key\":\"KRX-PLACEHOLDER\"},\"opendart\":{\"crtfc-key\":\"DART-PLACEHOLDER\"}}");
        CollectorSecrets s = CollectorSecrets.load(dir);
        assertThat(s.krxAuthKey().reveal()).isEqualTo("KRX-PLACEHOLDER");
        assertThat(s.openDartKey().reveal()).isEqualTo("DART-PLACEHOLDER");
        assertThat(s.toString()).doesNotContain("PLACEHOLDER");
    }

    @Test
    void 모르는_키가_있으면_파일_전체를_거부하고_값은_메시지에_없다() throws IOException {
        Files.writeString(dir.resolve("collector-secrets.json"),
                "{\"krx\":{\"auth-key\":\"K\"},\"brokers\":{\"kis\":{\"app-key\":\"BROKER-VALUE\"}}}");
        assertThatThrownBy(() -> CollectorSecrets.load(dir))
                .hasMessageContaining("brokers").hasMessageNotContaining("BROKER-VALUE");
        Files.writeString(dir.resolve("collector-secrets.json"), "{\"opendart\":{\"crtfc_key\":\"TYPO-VALUE\"}}");
        assertThatThrownBy(() -> CollectorSecrets.load(dir))
                .hasMessageContaining("opendart.crtfc_key").hasMessageNotContaining("TYPO-VALUE");
    }

    @Test
    void 한국투자_뉴스_조회_키를_읽고_계좌번호_칸은_거부한다() throws IOException {
        Files.writeString(dir.resolve("collector-secrets.json"),
                "{\"kis-news\":{\"app-key\":\"KISKEYEEEEEEEEEE\",\"app-secret\":\"KISSECRETFFFFFFFF\"}}");
        CollectorSecrets s = CollectorSecrets.load(dir);
        assertThat(s.kisNewsAppKey().reveal()).isEqualTo("KISKEYEEEEEEEEEE");
        assertThat(s.kisNewsAppSecret().reveal()).isEqualTo("KISSECRETFFFFFFFF");
        assertThat(s.krxAuthKey().isPresent()).isFalse();
        assertThat(s.toString()).doesNotContain("KISKEY").doesNotContain("KISSECRET");
        Files.writeString(dir.resolve("collector-secrets.json"),
                "{\"kis-news\":{\"app-key\":\"K\",\"app-secret\":\"S\",\"account-no\":\"ACCOUNT-VALUE\"}}");
        assertThatThrownBy(() -> CollectorSecrets.load(dir))
                .hasMessageContaining("kis-news.account-no").hasMessageNotContaining("ACCOUNT-VALUE");
        Files.writeString(dir.resolve("collector-secrets.json"), "{\"kis\":{\"app-key\":\"K\"}}");
        assertThatThrownBy(() -> CollectorSecrets.load(dir)).hasMessageContaining("kis");
    }

    @Test
    void 깨진_JSON은_값_없이_거부한다() throws IOException {
        Files.writeString(dir.resolve("collector-secrets.json"), "{\"krx\":{\"auth-key\":\"SECRET-PART");
        assertThatThrownBy(() -> CollectorSecrets.load(dir)).hasMessageNotContaining("SECRET-PART");
    }

    @Test
    void 프로세스용_secrets_json은_읽지_않는다() throws IOException {
        Files.writeString(dir.resolve("secrets.json"), "{\"krx\":{\"auth-key\":\"FROM-PROCESS-FILE\"}}");
        assertThat(CollectorSecrets.load(dir).krxAuthKey().isPresent()).isFalse();
    }
}

package com.autotrade.app.lab.experiment;

import static org.assertj.core.api.Assertions.assertThat;

import com.autotrade.sim.run.ExperimentJobStatus;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * 실험 진행 상태 화면(jobs.js)의 작업 종류 이름표가 실행 명령 종류({@link ExperimentJobStatus#COMMANDS})를 모두 덮는지 본다.
 * 새 명령을 더하고 이름표를 빠뜨리면 화면에 영문 키가 그대로 보인다(PR #210 architect m2).
 */
class ExperimentJobCommandLabelTest {

    private static final String JOBS_JS = "static/js/pages/jobs.js";
    private static final Pattern COMMAND_BLOCK = Pattern.compile("const COMMAND = \\{(.*?)\\};", Pattern.DOTALL);
    private static final Pattern KEY = Pattern.compile("'?([a-z][a-z-]*)'?\\s*:\\s*'");

    @Test
    void 모든_실행_명령에_화면_이름표가_있다() throws IOException {
        String js;
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(JOBS_JS)) {
            assertThat(in).as(JOBS_JS + "가 시험 클래스패스에 있어야 한다").isNotNull();
            js = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        Matcher block = COMMAND_BLOCK.matcher(js);
        assertThat(block.find()).as("jobs.js의 COMMAND 표").isTrue();
        Set<String> labelled = new LinkedHashSet<>();
        Matcher key = KEY.matcher(block.group(1));
        while (key.find()) {
            labelled.add(key.group(1));
        }

        assertThat(labelled).containsAll(ExperimentJobStatus.COMMANDS);
        assertThat(labelled).contains("recording-precheck");
    }
}

plugins {
    id("autotrade.pure-java-conventions")
}

// 실전 엔진 저장소(ADR-015, D-141): 엔진 전용 추가 기록 원장(JSON 줄·강제 기록·CRC32C·rseq), writer.lock, 기동 기록, Guard·명령 감사.
// 감시 긴급 경로(묶음 F)도 같은 원장 코드를 써야 해서 프로세스 모듈이 아니라 순수 모듈에 둔다(ADR-015 2.10). 실험실·게이트웨이·
// 수집기·실험 명령은 이 모듈에 의존하지 못한다(processIsolation 금지 목록과 ArchitectureTest).
dependencies {
    api(project(":autotrade-risk"))
    implementation(platform(libs.jackson.bom))
    implementation(libs.jackson.databind)
}

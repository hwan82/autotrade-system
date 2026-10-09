plugins {
    id("autotrade.pure-java-conventions")
}

// 실전 제어 명령 채널(ADR-006). 실험실(UI)이 실전 엔진에 명령을 보내는 유일한 통로의 메시지 형식과 소켓 입출력.
// 실전 엔진(서버)과 실험실(클라이언트)이 함께 쓰므로 어느 프로세스에도 속하지 않는 순수 모듈에 둔다(D-013).
dependencies {
    api(project(":autotrade-config"))
    implementation(platform(libs.jackson.bom))
    implementation(libs.jackson.databind)
}

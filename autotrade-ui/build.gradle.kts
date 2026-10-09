plugins {
    id("autotrade.java-conventions")
}

// 로컬 웹 대시보드 화면(D-020, ADR-005). 빌드 도구 없는 정적 HTML·CSS·ES 모듈만 담고,
// 실험실 프로세스(autotrade-app-lab)가 클래스패스의 static/ 아래를 서빙한다. 실전 엔진에는 들어가지 않는다(ADR-001).

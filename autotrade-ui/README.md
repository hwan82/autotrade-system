# autotrade-ui

로컬 웹 대시보드 화면입니다. 형태는 [D-020](../docs/decisions/D-020-ui-web-dashboard.md), 구현 방식은 [D-026](../docs/decisions/D-026-ui-frontend.md)·[ADR-005](../docs/adr/ADR-005-ui-dashboard.md)입니다.

- 빌드 도구 없는 정적 HTML·CSS·ES 모듈(`src/main/resources/static/`)이고, 실험실 프로세스(`autotrade-app-lab`)가 `127.0.0.1`에서 서빙합니다. 실전 엔진에는 들어가지 않습니다(ADR-001 2.1).
- 화면: 실험 비교(리더보드, 자산 곡선, 체결·왕복·거절 사유), 전략 프로필 편집(버전·해시), 설정(비밀값 마스킹).
- 외부 라이브러리·CDN을 쓰지 않습니다(CSP `'self'`). 서버 문자열은 `textContent`로만 넣습니다.
- 실전 관련 버튼(실전 활성화, PANIC SELL)은 아직 없습니다. 운영 화면은 별도 PR에서 2차 확인과 함께 만듭니다.

## 실행
- 실험실: `./gradlew :autotrade-app-lab:bootRun` 후 http://127.0.0.1:8480 (포트는 `AUTOTRADE_UI_PORT`)
- 샘플 데이터로 화면 미리 보기: `./gradlew :autotrade-app-lab:bootTestRun` (테스트 소스의 손으로 만든 `DEMO-` 실험, 임시 폴더 사용)

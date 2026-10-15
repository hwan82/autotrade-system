# autotrade-ui

로컬 웹 대시보드 화면입니다. 형태는 [D-020](../docs/decisions/D-020-ui-web-dashboard.md), 구현 방식은 [D-026](../docs/decisions/D-026-ui-frontend.md)·[ADR-005](../docs/adr/ADR-005-ui-dashboard.md)입니다.

- 빌드 도구 없는 정적 HTML·CSS·ES 모듈(`src/main/resources/static/`)이고, 실험실 프로세스(`autotrade-app-lab`)가 `127.0.0.1`에서 서빙합니다. 실전 엔진에는 들어가지 않습니다(ADR-001 2.1).
- 화면: 실험 생성(조합 실험 등록 파일 쓰기, 시장·스타일 필터로 조합 고르기, 실행은 실행 명령, D-074), 실험 비교(조합 리더보드, 선정 × 매매지표 교차표, 자산 곡선, 체결·왕복·거절 사유, 날짜별 선정 기록과 선정 결과 조회), 선정 프로필 편집(조건 행, 버전·해시, 저장 전 의미 동일 알림, D-060·D-074), 전략 프로필 편집(버전·해시), 설정(비밀값 마스킹), 실시간 모의(등록 파일 고르기, 시작·멈춤·끝내기, 계좌별 현금·평가금액·체결·실패, 최근 공백, 3초 폴링, D-076·ADR-010), 모니터링(PC 전체 CPU·메모리·드라이브·네트워크와 실험실 JVM 힙·GC·스레드, 임계 수준과 최근 임계 이벤트, SSE, D-090), 실전 운영(계좌별 Guard 상태, 실전 활성화 2차 확인, 내리기, PANIC SELL).
- 외부 라이브러리·CDN을 쓰지 않습니다(CSP `'self'`). 서버 문자열은 `textContent`로만 넣습니다.
- 실전 운영 화면은 명령을 실전 엔진의 명령 채널(설정 폴더 안 로컬 소켓)로 보내기만 합니다. 비밀번호·확인 문구 검증과 Guard 전이는 실전 엔진이 합니다(ADR-006). 실전 계좌가 하나라도 꺼짐이 아니면 상단 띠가 해칭 없는 적갈색 "실전 가동 중"으로 바뀝니다.

## 실행
- 실험실: `./gradlew :autotrade-app-lab:bootRun` 후 http://127.0.0.1:8480 (포트는 `AUTOTRADE_UI_PORT`)
- 실전 운영 화면: 실전 엔진 `./gradlew :autotrade-app-engine:run`을 같은 `AUTOTRADE_CONFIG_DIR`로 띄웁니다. 운영자 비밀번호 해시는 터미널에서 `autotrade-engine hash-password`(installDist 산출물)로 만들어 secrets.json에 넣습니다.
- 샘플 데이터로 화면 미리 보기: `./gradlew :autotrade-app-lab:bootTestRun` (테스트 소스의 손으로 만든 `DEMO-` 실험, 임시 폴더 사용)

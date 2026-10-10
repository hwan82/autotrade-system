# Python 수집기 자리

[D-019](../../docs/decisions/D-019-python-collector.md)(기획서 ARC-06)에 따라 **과거 데이터 오프라인 배치 수집만** 하는 자리입니다.

- Java 빌드(`./gradlew build`)와 4개 프로세스에 포함되지 않고, Java 쪽은 Python을 실행하지 않습니다.
- 실시간 수신(WebSocket), 증권사 SDK, 주문 API, 증권사 인증은 쓰지 않습니다. 증권사 키·토큰·계좌 정보를 읽거나 담지 않습니다.
- CI가 `scripts/ci/check-python-collectors.sh`로 위 항목(SDK·WebSocket import, 증권사 주소, 키 이름 대입)을 검사합니다.
- 결과는 버전 스냅샷 파일(스키마 + manifest: 버전, sha256, 출처, 라이선스, 수집 시각, 스크립트 버전)로만 전달하고, 형식은 2단계 data-engineer ADR을 따릅니다. 스냅샷 데이터는 저장소에 커밋하지 않습니다.
- 소스별 라이선스·약관은 [Q-007·Q-026 조사](../../docs/research/q-007-q-026-collector-licenses.md)와 D-050을 따릅니다(일봉은 KRX Open API, 재무는 OpenDART, 스크래핑 라이브러리 안 씀). 수집기 언어 유지 여부는 Q-037입니다.

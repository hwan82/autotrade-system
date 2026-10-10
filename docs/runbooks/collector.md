# 수집기 실행 절차 (일봉·상장주식수·재무)

[ADR-008](../adr/ADR-008-collector.md), D-050, D-051. 수집기는 4개 프로세스와 따로 도는 단발 명령이다. 실전 엔진·게이트웨이가 켜져 있어도 서로 영향이 없다(증권사 키를 읽지 않고 증권사 API를 부르지 않는다).

## 1. 준비 (한 번)

1. 인증키 발급(사용자 작업, 무료)
   - KRX Open API(`openapi.krx.co.kr`): 회원 가입 → 인증키 신청 → **서비스 이용 신청**: 유가증권 일별매매정보, 코스닥 일별매매정보, 코넥스 일별매매정보. 승인까지 시간이 걸릴 수 있다.
   - OpenDART(`opendart.fss.or.kr`): 인증키 신청(회원당 1개).
2. 설정 폴더(기본 `~/.autotrade`, Windows는 `C:\Users\<사용자>\.autotrade`, `AUTOTRADE_CONFIG_DIR`로 바꿀 수 있음)에 `collector-secrets.json`을 만든다. **`secrets.json`이 아니다.**

   ```json
   { "krx": { "auth-key": "<KRX_OPEN_API_AUTH_KEY>" }, "opendart": { "crtfc-key": "<OPENDART_CRTFC_KEY>" } }
   ```

   - 다른 항목을 넣으면 수집기가 파일 전체를 거부한다(오타·증권사 키 혼입 방지). 키 하나만 있어도 그 명령은 돈다.
   - 이 파일은 저장소에 넣지 않는다(`.gitignore`에 있음). 파일 권한은 본인만 읽게 둔다.

## 2. 첫 실행 확인 (KRX 요청 형식 `[가정]` 확인)

하루만 받아 응답 형식을 확인한다(저장소 루트에서).

```
./gradlew :autotrade-app-collector:run --args="krx-daily --from 2026-10-08 --to 2026-10-08"
./gradlew :autotrade-app-collector:run --args="dart-financials --from 2026-03-01 --to 2026-03-31"
```

- 성공하면 스냅샷 폴더와 품질 리포트 경로가 출력된다. 리포트(`experiments/_data-quality/<스냅샷ID>.md`)의 오류 건수와 "수집 요약"을 확인한다.
- `HTTP 401/403`, `OutBlock_1 목록이 없습니다`, `응답 날짜가 요청과 다릅니다`가 나오면 메시지를 그대로 스레드에 붙여 준다(키는 메시지에 나오지 않는다). 서비스 이용 승인 전이면 403이 날 수 있다 `[가정]`.

## 3. 전체 수집 (여러 날에 나눠 자동으로 이어짐)

```
./gradlew :autotrade-app-collector:run --args="krx-daily --from 2010-01-04"
./gradlew :autotrade-app-collector:run --args="dart-financials --from 2016-01-01"
```

- KRX는 하루 9,000회(약관 10,000회의 90%)에서 멈추고 **종료 코드 3**으로 끝난다. 받은 날은 캐시(`<설정 폴더>/collector-cache/`)에 남으니 다음 날 같은 명령을 다시 돌리면 이어 받는다. 2010년부터 전체는 이틀쯤 걸린다.
- OpenDART는 하루 10,000회, 또는 한도 초과(020) 응답을 받으면 그날 멈춘다.
- 전체 기간 스냅샷의 품질 리포트를 만들 때 메모리가 많이 든다. 메모리 부족이 나면 `JAVA_OPTS=-Xmx6g`를 주거나 실험에 필요한 기간만 `--from`으로 받는다(캐시는 그대로 쓰므로 다시 부르지 않는다).
- 수정 계수 표본 대조: 분할 사례(예: 2018-05-04 삼성전자 50:1) 전후 며칠의 수정 종가(`종가 × adj_factor`)를 HTS 수정주가 차트와 비교해 결과를 스레드에 남긴다.

## 4. 매일 운영 (장 마감 뒤)

순서: `krx-daily` → `dart-financials` → (ADR-007 구현 뒤) 선정 결과 일괄 명령.

- `krx-daily`는 기본 `--to`가 서울 18:00 전이면 어제, 뒤면 오늘이다. 18:30 이후에 돌리는 것을 권한다 `[가정]`.
- `dart-financials`는 어제까지 접수된 공시를 훑는다. **매일 돌려야** 정정 공시가 나오기 전에 원 공시 값을 받아 둔다(MD-10 기본 규칙). 건너뛴 날의 공시는 다음 실행에서 받지만, 그 사이 정정이 나오면 원 공시 값은 "값 모름"(대체 규칙 대상)이 된다.
- Windows 작업 스케줄러에 등록할 때는 `./gradlew :autotrade-app-collector:installDist` 뒤 `autotrade-app-collector/build/install/autotrade-collector/bin/autotrade-collector.bat krx-daily --report-dir <저장소>/experiments/_data-quality`처럼 설치본을 쓴다.
- 종료 코드: 0 완료, 1 오류(키·형식·네트워크), 2 사용법 오류, 3 호출 상한·한도 초과(다음 날 이어 받음).

## 5. 결과물 위치

| 무엇 | 위치 | 저장소에 넣나 |
|---|---|---|
| 일봉 스냅샷 | `<설정 폴더>/snapshots/krx-daily/<ID>/` | 아니오(원자료, D-050 제3자 제공 금지) |
| 재무 스냅샷 | `<설정 폴더>/snapshots/dart-financials/<ID>/` | 아니오 |
| 응답 캐시·호출 기록 | `<설정 폴더>/collector-cache/` | 아니오 |
| 품질 리포트 | `experiments/_data-quality/<ID>.md` | 예(집계 건수만) |

옵션 전체는 `CollectorMain` 주석 또는 인자 없이 실행했을 때 나오는 사용법을 본다.

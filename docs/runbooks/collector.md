# 수집기 실행 절차 (일봉·상장주식수·재무)

[ADR-008](../adr/ADR-008-collector.md), D-050, D-051. 수집기는 4개 프로세스와 따로 도는 단발 명령이다. 증권사 키를 읽지 않고 증권사 API를 부르지 않지만, **같은 PC의 CPU·메모리·디스크를 실전 엔진과 나눠 쓴다.** 그래서 수집기는 스스로 물러난다(ADR-008 2.7): 국내·미국 장 시간 보호 구간에는 실행하지 않고, 힙은 2GB로 고정, 디스크 여유가 20GB 미만이면 멈춘다. 감시 프로세스는 아직 수집기를 멈추지 못한다(Q-041).

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

- KRX는 하루 9,000회(약관 10,000회의 90%, `--max-calls`는 10,000까지)에서 멈추고 **종료 코드 3**으로 끝난다. 하루 호출 수는 `<설정 폴더>/collector-state/`에 키 단위로 기록돼 `--cache-dir`를 바꿔도 합산된다. 받은 날은 캐시(`<설정 폴더>/collector-cache/`)에 남으니 다음 날 같은 명령을 다시 돌리면 이어 받는다. 2010년부터 전체는 이틀쯤 걸린다.
- `dart-financials`는 훑은 구간을 기억하므로 2장에서 짧게 돌린 뒤 `--from`을 앞당겨도 앞 구간을 빠짐없이 훑는다(이미 훑은 구간은 다시 부르지 않는다).
- OpenDART는 하루 10,000회, 또는 한도 초과(020) 응답을 받으면 그날 멈춘다.
- 힙은 2GB로 고정돼 있다. 수집기는 스냅샷 쓰기와 품질 리포트를 한 줄씩 읽어 처리하므로 2010년부터 전체(약 1,100만 행)도 이 안에서 돈다. 메모리 부족(`OutOfMemoryError`)이 나면 **상한을 올리지 말고** 메시지를 스레드에 붙여 준다. 실험실이 전 기간 스냅샷을 통째로 읽을 때는 메모리가 모자랄 수 있다(ADR-008 4장).
- 전체 수집은 이틀 이상 걸리므로 장 시간 보호 구간에 걸려 멈출 수 있다(종료 코드 4). 구간이 끝난 뒤 같은 명령을 다시 돌리면 이어 받는다.
- 수정 계수 표본 대조: 분할 사례(예: 2018-05-04 삼성전자 50:1) 전후 며칠의 수정 종가(`종가 × adj_factor`)를 HTS 수정주가 차트와 비교해 결과를 스레드에 남긴다.

## 3.5 실행 시간과 장 시간 보호 구간

수집기는 아래 구간(거래일만, 거래소 캘린더 기준)에는 시작하지 않고, 실행 중 들어서면 다음 호출 전에 멈춘다(종료 코드 4).

| 시장 | 서울 시각 보호 구간 |
|---|---|
| 국내(KRX) | 08:00~16:00 (정규장 09:00~15:30의 60분 전~30분 뒤) |
| 미국 | 서머타임 중 21:30~05:30, 아닐 때 22:30~06:30 |

- 매일 실행은 **서울 18:30~21:30 사이**에 맞춘다(서머타임이 아니면 22:30 전).
- 실전 엔진을 끈 날에만 `--allow-during-session`으로 풀 수 있다.
- 거래소 캘린더 수록 범위 밖이면(KRX 캘린더는 2026-12-31까지) 장 시간인지 판단할 수 없어 멈춘다. 연말 전에 캘린더를 갱신한다(data-engineer).

## 4. 매일 운영 (장 마감 뒤)

순서: `krx-daily` → `dart-financials` → (ADR-007 구현 뒤) 선정 결과 일괄 명령.

- `krx-daily`는 기본 `--to`가 서울 18:00 전이면 어제, 뒤면 오늘이다. 18:30 이후, 미국 장 보호 구간 전에 돌린다(3.5).
- `dart-financials`는 어제까지 접수된 공시를 훑는다. **매일 돌려야** 정정 공시가 나오기 전에 원 공시 값을 받아 둔다(MD-10 기본 규칙). 건너뛴 날의 공시는 다음 실행에서 받지만, 그 사이 정정이 나오면 원 공시 값은 "값 모름"(대체 규칙 대상)이 된다.
- 예약 작업은 설치본을 **낮은 우선순위로** 돌린다. `./gradlew :autotrade-app-collector:installDist` 뒤:
  - Windows 작업 스케줄러: 동작에 `cmd /c start "" /low /wait <저장소>\autotrade-app-collector\build\install\autotrade-collector\bin\autotrade-collector.bat krx-daily --report-dir <저장소>\experiments\_data-quality --experiments-dir <저장소>\experiments --keep-snapshots 3`. 작업 설정의 우선순위도 낮게(기본 7) 둔다.
  - Linux·macOS: `nice -n 19 <저장소>/autotrade-app-collector/build/install/autotrade-collector/bin/autotrade-collector krx-daily --report-dir <저장소>/experiments/_data-quality --experiments-dir <저장소>/experiments --keep-snapshots 3`
  - 힙 상한(2GB)은 설치본 실행 스크립트에 들어 있다. `JAVA_OPTS`로 올리지 않는다.
- 종료 코드: 0 완료, 1 오류(키·형식·네트워크), 2 사용법 오류, 3 호출 상한·한도 초과(다음 날 이어 받음), 4 실전 PC 자원 보호로 멈춤(장 시간 보호 구간, 디스크 여유 부족. 구간이 끝나거나 공간을 비운 뒤 다시 돌리면 이어 받음).

## 5. 결과물 위치

| 무엇 | 위치 | 저장소에 넣나 |
|---|---|---|
| 일봉 스냅샷 | `<설정 폴더>/snapshots/krx-daily/<ID>/` | 아니오(원자료, D-050 제3자 제공 금지) |
| 재무 스냅샷 | `<설정 폴더>/snapshots/dart-financials/<ID>/` | 아니오 |
| 응답 캐시 | `<설정 폴더>/collector-cache/` | 아니오 |
| 하루 호출 기록 | `<설정 폴더>/collector-state/budget/` | 아니오 |
| 품질 리포트 | `experiments/_data-quality/<ID>.md` | 예(집계 건수만) |

### 5.1 디스크 용량과 보관

- 추정 `[추론]`: 2010년부터 전체 일봉 스냅샷 하나 약 1,100만 행, 수백 MB. 원문 캐시(KRX 날짜·시장별 응답)는 수 GB. 재무 스냅샷·캐시는 수십~수백 MB. 매일 새 스냅샷을 만들면 정리하지 않을 때 한 달에 수 GB~수십 GB가 쌓인다.
- 시작할 때와 스냅샷을 쓰기 전에 캐시·스냅샷 폴더 디스크 여유가 20GB(`--min-free-gb`로 바꿈) 미만이면 멈춘다. 실전 엔진의 상태 기록·DB가 같은 디스크를 쓸 수 있기 때문이다.
- `--keep-snapshots K`를 주면 **일봉(`krx-daily`)만** 최근 K개, 방금 만든 것, `experiments/`(`--experiments-dir`) 아래 파일에 ID가 적힌 것만 남기고 옛 스냅샷 폴더를 지운다. 품질 리포트(`_data-quality`)는 참조로 치지 않는다. 실험 폴더를 못 찾으면 지우지 않는다. **실험실 DB에만 적힌 스냅샷 참조는 보지 못한다**: 실험을 돌리면 `experiments/<실험ID>/`에 쓴 스냅샷 ID를 꼭 남긴다. 주지 않으면 지우지 않는다. 지운 일봉 스냅샷은 같은 `--from`·`--to`로 다시 만들면 원문 캐시가 남아 있는 한 같은 ID로 복원된다. **재무 스냅샷(`dart-financials`)은 지우면 같은 내용으로 다시 만들 수 없어(공시·정정·대기 확정이 실행마다 늘어남) `--keep-snapshots`를 줘도 지우지 않는다.** 크기가 작아 그대로 둔다. 손으로 지우면 되돌릴 수 없다. 원문 캐시는 지우지 않는다(다시 받으려면 호출 한도를 다시 써야 한다).
- 실험에 쓴 스냅샷은 실험 설정(`experiments/<실험ID>/`)에 스냅샷 ID를 적어 두어야 정리 대상에서 빠진다.
- 같은 하위 명령을 동시에 두 개 돌려도 호출 기록은 잠금으로 합산된다. 그래도 같은 캐시를 함께 쓰므로 한 번에 하나만 돌린다.

옵션 전체는 `CollectorMain` 주석 또는 인자 없이 실행했을 때 나오는 사용법을 본다.

# Python 수집기 자리

D3(Python 수집기 허용) 추천안에 따라 비워 둔 자리입니다. 증권사 SDK가 Python만 제공하거나 일봉 수집이 Python 쪽이 쉬울 때 이곳에 둡니다.

- Java 빌드(`./gradlew build`)에는 포함되지 않습니다.
- 수집기는 시세·일봉 수신만 하고 **주문 API를 호출하지 않습니다**(안전 불변식 8).
- API Key·계좌번호·토큰은 코드·로그·커밋에 남기지 않고 `runtime-config.json`(저장소 밖)에서 읽습니다.
- 산출물 형식(녹화·일봉 스키마)은 2단계 data-engineer ADR을 따릅니다.

# 전략 프로필 (YAML)

파일 배치는 `strategies/<프로필ID>/v<버전>.yaml`입니다([ADR-005](../docs/adr/ADR-005-ui-dashboard.md) 4장). 대시보드의 전략 프로필 화면이 이 폴더를 읽고 씁니다.

- 저장된 버전 파일은 고치지 않습니다. 바꾸려면 새 버전을 만듭니다(새 해시). 실험 결과가 해시로 프로필을 가리키기 때문입니다.
- 버전 사이에 `market`·`style`은 바꿀 수 없습니다. 다른 시장·스타일은 복제해 새 ID로 만듭니다.
- 파라미터는 점으로 이은 키와 문자열 값입니다(예: `entry.signal: range-breakout`, `exit.stop-loss-pct: "3"`).

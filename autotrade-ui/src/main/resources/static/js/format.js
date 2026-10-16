// 표시 형식. 금액·비율은 서버가 문자열로 주며, 여기서는 보여 주기만 한다(계산하지 않음).
const ZONES = { KRX: 'Asia/Seoul', US: 'America/New_York' };

export function money(value, currency) {
  if (value === null || value === undefined) return '–';
  const n = Number(value);
  const digits = currency === 'KRW' ? 0 : 2;
  return new Intl.NumberFormat('ko-KR', { style: 'currency', currency, minimumFractionDigits: digits, maximumFractionDigits: digits }).format(n);
}

export function number(value, digits = 0) {
  if (value === null || value === undefined) return '–';
  return new Intl.NumberFormat('ko-KR', { maximumFractionDigits: digits }).format(Number(value));
}

/** 소수 비율(0.05) → "5.00%". */
export function pct(value, digits = 2) {
  if (value === null || value === undefined) return '–';
  return `${(Number(value) * 100).toFixed(digits)}%`;
}

export function ratio(value, digits = 2) {
  if (value === null || value === undefined) return '–';
  return Number(value).toFixed(digits);
}

/** 부호 색: 국내 관례대로 이익 빨강, 손실 파랑. */
export function signClass(value) {
  if (value === null || value === undefined) return null;
  const n = Number(value);
  return n > 0 ? 'up' : n < 0 ? 'down' : null;
}

function fmtZone(iso, zone) {
  return new Intl.DateTimeFormat('ko-KR', {
    timeZone: zone, month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false,
  }).format(new Date(iso));
}

/** 시각 표시(UI-07). 국내는 KST, 미국은 현지(ET)와 KST를 함께. */
export function time(iso, market) {
  if (!iso) return '–';
  const kst = `${fmtZone(iso, 'Asia/Seoul')} KST`;
  if (market === 'US') return `${fmtZone(iso, ZONES.US)} ET (${kst})`;
  return kst;
}

export function shortHash(hash) {
  return hash ? hash.slice(0, 12) : '–';
}

export const STATUS = { COMPLETED: '완료', FAILED: '실패', RUNNING: '실행 중', PENDING: '대기', CANCELLED: '취소' };
export const MARKET = { KRX: '국내(KRX)', US: '미국(US)' };
export const STYLE = { INTRADAY: '단타', SWING: '스윙', POSITION: '중기' };
export const REFRESH = { PRE_OPEN_DAILY: '매일 장전', POST_CLOSE_DAILY: '매일 장 마감 뒤', POST_CLOSE_WEEKLY: '주간(주 마지막 거래일 마감 뒤)' };
/** 선정 결과가 없는 사유(ADR-007 2.6). 그날 그 계좌는 신규 진입 0건이고 보유 관리·청산은 그대로다. */
export const VALIDITY = {
  VALID: '결과 있음', MISSING: '결과 없음', NOT_YET: '적용 전', EXPIRED: '만료', HASH_MISMATCH: '해시 불일치',
  STALE_INPUT: '입력 데이터 오래됨', MALFORMED: '형식 오류', PRODUCER_FAILED: '생산 실패',
};

/** 조합 이름(ADR-007 5장 ui-dev): 선정 ID·버전(없으면 "선정 없음") + 전략 ID·버전. */
export function combinationLabel(r) {
  const sel = r.selectionId ? `${r.selectionId} v${r.selectionVersion}` : '선정 없음';
  return `${sel} × ${r.profileId} v${r.profileVersion}`;
}

/** 차이 표시. 비율 지표는 %p, 그 밖은 소수 둘째 자리. 양수에 + 부호. */
export function delta(value, isRatio, digits = 2) {
  if (value === null || value === undefined) return '–';
  const n = Number(value) * (isRatio ? 100 : 1);
  return `${n > 0 ? '+' : ''}${n.toFixed(digits)}${isRatio ? '%p' : ''}`;
}
// 실험 실행 방식(PR #82 QA R2). 리플레이는 데이터 버전 앞뒤 표지로 어떤 시세를 다시 돌렸는지 가른다:
// 일봉 스냅샷 경로(DailyBarMarketDataSet "<스냅샷>#DAILY-PATH-V1#기간"), 녹화(RecordedMarketDataSet "REC-…"), 합성(SyntheticMarket "SYNTH-…").
export function clockModeLabel(e) {
  if (e.clockMode !== 'REPLAY') return '실시간 모의';
  const v = e.dataSnapshotVersion || '';
  if (v.includes('#DAILY-PATH-')) return '리플레이(일봉 경로)';
  if (v.startsWith('REC-')) return '녹화 리플레이';
  if (v.startsWith('SYNTH-')) return '리플레이(합성 시세)';
  return '리플레이';
}

export const SIDE = { BUY: '매수', SELL: '매도' };
export const INTENT = { ENTRY: '진입', EXIT: '청산', STOP_LOSS: '손절' };

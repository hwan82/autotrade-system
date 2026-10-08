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
export const STYLE = { INTRADAY: '단타', SWING: '스윙' };
export const SIDE = { BUY: '매수', SELL: '매도' };
export const INTENT = { ENTRY: '진입', EXIT: '청산', STOP_LOSS: '손절' };

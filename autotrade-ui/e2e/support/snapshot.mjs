// 합성 일봉 스냅샷(D-101): ExperimentCreationEndToEndTest와 같은 2종목 80거래일이다(손 계산용 값, 실제 시세 아님).
// 거래일은 저장소의 KRX 캘린더(krx.json)에서 주말과 휴장일을 빼서 고른다(ExchangeCalendar.tradingDays와 같은 규칙).
import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import { REPO } from './paths.mjs';

const CALENDAR = path.join(REPO, 'autotrade-marketdata', 'src', 'main', 'resources', 'com', 'autotrade', 'marketdata',
  'calendar', 'krx.json');

export function tradingDays(from, count) {
  const cal = JSON.parse(fs.readFileSync(CALENDAR, 'utf8'));
  const holidays = new Set(cal.holidays.map(h => h.date));
  const out = [];
  for (let d = new Date(`${from}T00:00:00Z`); out.length < count; d.setUTCDate(d.getUTCDate() + 1)) {
    const iso = d.toISOString().slice(0, 10);
    const dow = d.getUTCDay();
    if (dow !== 0 && dow !== 6 && !holidays.has(iso)) out.push(iso);
  }
  return out;
}

const sha256 = file => crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex');

/** 설정 폴더 아래 snapshots/krx-daily/<id>/에 스냅샷을 쓰고 첫·마지막 거래일을 돌려준다. */
export function writeSnapshot(configDir, id) {
  const days = tradingDays('2026-01-02', 80);
  const dir = path.join(configDir, 'snapshots', 'krx-daily', id);
  fs.mkdirSync(dir, { recursive: true });
  let bars = 'market,code,date,open,high,low,close,volume,adj_factor\n';
  days.forEach((d, i) => {
    const bc = 10_000 + 10 * i;
    bars += `KRX,000010,${d},${bc - 5},${bc + 5},${bc - 10},${bc},${i < 20 ? 100 : 400},1\n`;
    const ac = 10_000 + 100 * i;
    bars += `KRX,000020,${d},${ac - 50},${ac + 50},${ac - 100},${ac},1000,1\n`;
  });
  const barsFile = path.join(dir, 'daily_bars.csv');
  fs.writeFileSync(barsFile, bars, 'utf8');
  const histFile = path.join(dir, 'instrument_history.csv');
  fs.writeFileSync(histFile, 'market,code,name,kind,board,valid_from,valid_to\n'
    + 'KRX,000010,나,STOCK,KOSDAQ/우량기업부,2000-01-04,\n'
    + 'KRX,000020,가,STOCK,KOSPI,2000-01-04,\n', 'utf8');
  const manifest = {
    snapshotId: id, schemaVersion: 1, createdAt: '2026-05-01T09:00:00Z', scriptVersion: '0.1.0',
    sources: [{ name: '화면 시험', url: 'https://example.invalid', license: '시험용 합성 값', collectedAt: '2026-05-01T08:00:00Z' }],
    files: [
      { name: 'daily_bars.csv', sha256: sha256(barsFile), rows: days.length * 2 },
      { name: 'instrument_history.csv', sha256: sha256(histFile), rows: 2 },
    ],
    coverage: { firstDate: days[0], lastTradingDate: days[days.length - 1] },
  };
  fs.writeFileSync(path.join(dir, 'manifest.json'), JSON.stringify(manifest, null, 1), 'utf8');
  return { first: days[0], last: days[days.length - 1] };
}

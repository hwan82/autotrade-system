// 화면 오류 감시: 잡히지 않은 예외(pageerror)와 콘솔 오류를 모은다. 시험이 일부러 만든 HTTP 오류(예: 409)만 허용한다.
import { expect } from '@playwright/test';

export function watchErrors(page) {
  const errors = [];
  const allowed = new Set();
  page.on('pageerror', e => errors.push(`pageerror: ${e.message}`));
  page.on('console', m => { if (m.type() === 'error') errors.push(`console: ${m.text()}`); });
  return {
    /** 이 상태 코드의 리소스 오류 콘솔 줄은 의도한 것이다. */
    expectHttp(status) { allowed.add(status); },
    expectNone() {
      const unexpected = errors.filter(e => {
        const m = /status of (\d{3})/.exec(e);
        return !(m && e.startsWith('console:') && allowed.has(Number(m[1])));
      });
      expect(unexpected, '화면 오류').toEqual([]);
    },
  };
}

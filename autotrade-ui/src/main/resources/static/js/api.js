// 실험실 REST API 호출. 변경 요청에는 CSRF 토큰을 붙인다(ADR-005 3장).
let session = null;

export async function loadSession() {
  session = await request('GET', '/api/session');
  return session;
}

export async function request(method, url, body) {
  const headers = { Accept: 'application/json' };
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  if (method !== 'GET' && session) headers['X-CSRF-Token'] = session.csrfToken;
  const res = await fetch(url, {
    method, headers, body: body === undefined ? undefined : JSON.stringify(body),
    credentials: 'same-origin', cache: 'no-store',
  });
  const text = await res.text();
  const data = text ? JSON.parse(text) : null;
  if (!res.ok) {
    const err = new Error((data && data.message) || `요청 실패(${res.status})`);
    err.status = res.status;
    err.code = data && data.code;
    throw err;
  }
  return data;
}

export const get = (url) => request('GET', url);
export const post = (url, body) => request('POST', url, body);
export const put = (url, body) => request('PUT', url, body);

/** 페이징 목록을 끝까지 읽는다(200개가 넘어도 빠지지 않게, PR #49 architect 권고 3). url에는 이미 ?가 있어야 한다. */
export async function getAll(url) {
  const out = [];
  for (let page = 0; ; page++) {
    const res = await get(`${url}&page=${page}&size=200`);
    out.push(...res.items);
    if (out.length >= res.total || res.items.length === 0) return out;
  }
}

// 쿠키·CSRF·엔벨로프 검사를 한곳에 모은다. 시나리오는 도메인만 이야기하게 한다.
import http from 'k6/http';
import { check } from 'k6';

import { BASE, TOKENS } from './config.js';

/** 성공 엔벨로프에서 data를 꺼낸다. 모양이 다르면 null. */
export function okEnvelope(res) {
  if (res.status < 200 || res.status >= 300) return null;
  if (res.status === 204 || res.body === null || res.body === '') return null;
  let parsed;
  try {
    parsed = res.json();
  } catch (e) {
    return null;
  }
  if (parsed.success !== true) return null;
  if (parsed.error !== undefined) return null;
  return parsed.data === undefined ? null : parsed.data;
}

/** 오류 엔벨로프에서 error를 꺼낸다. 모양이 다르면 null. */
export function errorOf(res) {
  let parsed;
  try {
    parsed = res.json();
  } catch (e) {
    return null;
  }
  if (parsed.success !== false) return null;
  if (parsed.data !== undefined) return null;
  const err = parsed.error;
  if (!err || typeof err.code !== 'string' || typeof err.traceId !== 'string') return null;
  if (!Array.isArray(err.fieldErrors)) return null;
  return err;
}

/**
 * 상태 코드와 엔벨로프를 한 번에 본다.
 *
 * @param want {number|{status:number,code:string}} 숫자면 성공 기대, 객체면 오류 코드까지 본다
 * @returns 통과했으면 true
 */
export function expect(res, label, want) {
  const wantStatus = typeof want === 'number' ? want : want.status;
  const wantCode = typeof want === 'number' ? null : want.code;

  const conditions = {};
  conditions[`${label} → ${wantStatus}`] = (r) => r.status === wantStatus;

  if (wantCode === null) {
    conditions[`${label} 성공 엔벨로프`] = (r) =>
      r.status === 204 ? r.body === null || r.body === '' : okEnvelope(r) !== null;
  } else {
    conditions[`${label} error.code=${wantCode}`] = (r) => {
      const err = errorOf(r);
      return err !== null && err.code === wantCode;
    };
  }

  const passed = check(res, conditions);
  if (!passed) {
    console.error(`[FAIL] ${label} status=${res.status} body=${String(res.body).slice(0, 400)}`);
  }
  return passed;
}

/** 지정 회원으로 요청하는 세션. 쿠키 항아리는 VU마다 하나이므로 헤더로 명시한다. */
export function session(memberId) {
  const token = TOKENS[String(memberId)];
  if (!token) {
    throw new Error(`tokens.json에 member ${memberId}가 없다. mint-tokens.sh 인자를 확인하라`);
  }

  function baseParams(kind, opts) {
    const params = Object.assign({ tags: { kind: kind } }, opts || {});
    params.cookies = Object.assign({ access_token: token }, params.cookies || {});
    return params;
  }

  // CSRF 토큰은 세션당 한 번만 받아 캐시한다. 쓰기마다 예비 GET을 날리면 그 GET들이
  // list 부류 지연 통계에 섞여 측정값을 오염시킨다.
  let cachedXsrf = null;

  /** XSRF-TOKEN 쿠키를 얻어 온다. 상태를 바꾸는 요청 전에 필요하다. */
  function csrf() {
    if (cachedXsrf !== null) {
      return cachedXsrf;
    }
    // 태그를 csrf로 따로 붙여 임계값 대상에서 빠지게 한다.
    http.get(`${BASE}/v1/collections`, { tags: { kind: 'csrf' }, cookies: { access_token: token } });
    const cookies = http.cookieJar().cookiesForURL(BASE);
    const values = cookies['XSRF-TOKEN'];
    if (!values || values.length === 0) {
      throw new Error('XSRF-TOKEN 쿠키를 받지 못했다');
    }
    cachedXsrf = values[0];
    return cachedXsrf;
  }

  function writeParams(kind, opts) {
    const params = baseParams(kind, opts);
    params.headers = Object.assign(
      { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': csrf() },
      params.headers || {}
    );
    return params;
  }

  return {
    csrf: csrf,
    get: (path, kind, opts) => http.get(BASE + path, baseParams(kind, opts)),
    post: (path, body, kind, opts) =>
      http.post(BASE + path, body === null ? null : JSON.stringify(body), writeParams(kind, opts)),
    patch: (path, body, kind, opts) =>
      http.patch(BASE + path, body === null ? null : JSON.stringify(body), writeParams(kind, opts)),
    del: (path, kind, opts) => http.del(BASE + path, null, writeParams(kind, opts)),
    /** 쿠키·CSRF 없이 보낸다. 401·403 계약 확인용. */
    anon: (method, path, kind, opts) =>
      http.request(method, BASE + path, null, Object.assign({ tags: { kind: kind } }, opts || {})),
  };
}

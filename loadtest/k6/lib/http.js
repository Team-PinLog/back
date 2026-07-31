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

  /**
   * XSRF-TOKEN을 항아리(jar)에서 매번 새로 읽는다. 캐시하면 안 된다 —
   * 서버(CsrfCookieFilter)가 매 요청 토큰을 재발급해 응답마다 XSRF-TOKEN 쿠키가
   * 회전하므로, 한 번 받은 값을 들고 있으면 다음 요청이 자동으로 실어 보내는 쿠키와
   * 어긋나 403이 난다. 헤더와 쿠키는 같은 요청에서 같은 값이어야 하고 그 원본은 항상
   * 항아리다. 항아리는 VU 공유라 어느 세션이 예비 GET을 했는지는 무관하다 — 값만 맞으면 된다.
   *
   * 예비 GET은 항아리에 XSRF-TOKEN이 아예 없을 때 한 번만 나간다. kind: csrf 태그로
   * 지연 임계값 대상에서 빠진다.
   */
  function csrf() {
    let cookies = http.cookieJar().cookiesForURL(BASE);
    if (!cookies['XSRF-TOKEN'] || cookies['XSRF-TOKEN'].length === 0) {
      http.get(`${BASE}/v1/collections`, { tags: { kind: 'csrf' }, cookies: { access_token: token } });
      cookies = http.cookieJar().cookiesForURL(BASE);
    }
    const values = cookies['XSRF-TOKEN'];
    if (!values || values.length === 0) {
      throw new Error('XSRF-TOKEN 쿠키를 받지 못했다');
    }
    return values[0];
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
    /**
     * access_token 쿠키와 X-XSRF-TOKEN 헤더 없이 보낸다. 401·403 계약 확인용.
     * VU 공유 항아리의 XSRF-TOKEN 쿠키까지 막지는 않지만, 서버 판정 기준은
     * "인증 쿠키 부재 → 401"과 "헤더-쿠키 불일치(헤더 부재 포함) → 403"이라 영향이 없다.
     */
    anon: (method, path, kind, opts) =>
      http.request(method, BASE + path, null, Object.assign({ tags: { kind: kind } }, opts || {})),
  };
}

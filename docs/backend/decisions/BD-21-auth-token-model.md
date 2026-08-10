# BD-21. 인증 토큰 모델 — JWT를 `HttpOnly` 쿠키로 전달, Refresh는 Redis에 회전 발급

- **상태**: Accepted
- **날짜**: 2026-07-27 (`Team-PinLog/docs` `e0ba57b`·`4b0d90f` 쿠키 기반 개정)
- **작성 시점**: 2026-07-27 — 결정 이후에 정리
- **관련**: Jira 작업
- **공용 계약**: [11_인증_설계](https://github.com/Team-PinLog/docs/blob/main/static/11_인증_설계.md) (결정 근거의 원본) · [08_API_명세 §1.1·§1.7·§1.8](https://github.com/Team-PinLog/docs/blob/main/static/08_API_명세.md)

## 맥락

MVP 로그인은 Google·Kakao·Naver 소셜 로그인만 제공한다. 소셜 콜백 이후 세션을 어떻게 유지하고 **토큰을 브라우저 어디에 둘 것인가**가 결정 대상이었다.

두 가지 제약이 있었다.

- **JWT는 자체적으로 폐기되지 않는다.** 서명이 유효하고 만료 전이면 토큰만으로는 거부할 방법이 없는데, 로그아웃과 탈퇴는 즉시 반영되어야 한다.
- **Context 본문은 개인정보다.** 개인 데이터를 다루므로 토큰 탈취의 파급이 크다.

## 선택지

토큰 보관 위치는 **XSS와 CSRF의 맞교환**이다. 비교와 IETF 권고 인용은 [11_인증_설계 §3](https://github.com/Team-PinLog/docs/blob/main/static/11_인증_설계.md)에 있다.

| 안 | 채택하지 않은 이유 |
|---|---|
| Bearer 헤더 + `localStorage` | XSS로 토큰이 유출되면 공격자가 임의 API를 장기간 호출할 수 있다. BFF를 두는 목적이 토큰을 브라우저에서 치우는 것인데 다시 내려주면 이점이 상쇄된다 |
| 서버 세션 + 세션 ID | 구현이 가장 단순하고 로그아웃도 확실하나, 향후 네이티브 앱 확장을 고려해 JWT를 유지했다 |
| Access는 메모리 · Refresh만 쿠키 | Access가 XSS에 노출되는 구간이 남는다. 둘 다 쿠키에 두는 편이 단순하고 일관된다 |

## 결정

**`HttpOnly` 쿠키로 전달한다. 제약으로 주어짐** — 공용 계약이 정한 값이며 백엔드는 그 안에서 구현 경계를 지킨다.

| 항목 | 값 |
|---|---|
| 인증 방식 | JWT. Access **30분** / Refresh **7일** |
| 토큰 전달 | `HttpOnly` + `Secure` + `SameSite=Lax` 쿠키. **응답 본문에 토큰을 담지 않는다** |
| Refresh 저장 | Redis. **회전 발급**(재발급 시 이전 토큰 무효화) |
| Refresh 쿠키 범위 | `Path=/api/core/v1/auth` — 일반 API 요청에 실리지 않는다 |
| CSRF | `XSRF-TOKEN` 쿠키 → `X-XSRF-TOKEN` 헤더. 불일치 시 **403** |
| 로그인 표시 | `logged_in=1` 쿠키(`HttpOnly` 아님). **UI 힌트 전용, 인가 판단 금지** |
| 오리진 | 프론트와 API가 같은 오리진. CORS·`SameSite=None` 불필요 |

**만료 수치 30분 / 7일은 기본값 수용이다.** 널리 쓰이는 값을 그대로 받았고 이 서비스의 위협 모델에서 도출하지 않았다.

함께 확정된 것:

- 개인 API에서 사용자 ID를 Query나 Body로 받지 않는다. 서버가 **쿠키로** 식별한다.
- 내부 `member.id`는 응답에 포함하지 않는다([BD-14](BD-14-identifier-concealment.md)).
- 인증 Endpoint는 `Set-Cookie`로만 응답하므로 공통 봉투([BD-03](BD-03-api-response-envelope.md))가 적용되지 않는다.

## 결과

**감수하는 것**

- **CSRF 방어가 새로 필요하다.** 쿠키를 택한 대가다. 상태를 바꾸는 모든 요청이 `X-XSRF-TOKEN`을 검증해야 하고 누락하면 뚫린다.
- **`403`이 되살아났다.** 자원 접근 권한 실패는 여전히 `404`지만([BD-13](BD-13-public-boundary-query-dto-split.md)), CSRF 실패는 `403`이다. 한 상태 코드가 두 의미를 갖지 않도록 용도를 갈라 지켜야 한다.
- **무효화 지연** — 로그아웃해도 발급된 Access는 최대 30분 유효하다. 즉시 차단하려면 별도 블랙리스트가 필요하다.
- **Redis 의존** — 장애 시 재발급이 막혀 세션이 30분 안에 끊긴다. 인증 경로가 캐시 계층의 가용성에 묶인다.
- **같은 오리진 전제** — 프론트와 API를 분리 배포하면 `SameSite`와 CORS 자격증명 설정을 다시 설계해야 한다.
- **envelope 예외** — 인증 Endpoint의 성공 응답만 형식이 다르다(봉투 없음). 다만 그 응답이 전부 본문 없는 `302`·`204`라서 `ApiResponseBodyAdvice`가 이미 통과시키므로 **제외 장치는 필요 없다.** 대신 "성공 응답에 본문을 만들지 않는다"가 계약이 되어, 나중에 본문을 추가하려면 봉투 적용 여부를 다시 판단해야 한다.
- **`@RestControllerAdvice` 밖의 응답** — Security의 401·403 entry point는 공통 envelope를 거치지 않으므로 직접 `ApiResponse.fail(...)`을 만들어야 한다([BD-06](BD-06-framework-error-mapping.md)).

**재검토 트리거**

- 만료 수치는 근거 없이 채택했으므로 **실사용 데이터(재발급 빈도·세션 이탈)가 쌓이면 한 번은 검토해야 한다.** 기본값을 두는 것 자체가 결정임을 잊지 않기 위해 남긴다.
- 즉시 로그아웃이 보안 요건이 되면 → Access 만료 단축 또는 블랙리스트 도입.
- 프론트를 다른 오리진에 배포하게 되면 → 쿠키 속성과 CORS를 함께 재설계한다.

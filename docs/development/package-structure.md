# 패키지 구조 규약

시작 절차와 PR 규칙은 [CONTRIBUTING.md](../../CONTRIBUTING.md)를 따릅니다. 이 문서는 백엔드 소스 패키지의 배치 기준입니다.

## 기본 원칙

- **빈 패키지를 미리 만들지 않습니다.** `.gitkeep`으로 골격만 커밋하지 않고, 실제 클래스가 생길 때 그 패키지를 만듭니다. 이 문서가 "만들어질 위치"의 단일 기준입니다.
- 새 클래스는 **이 문서에 정의된 위치에만** 둡니다. 표에 없는 새 도메인·계층이 필요하면 파일을 만들기 전에 이 문서를 먼저 갱신하고 팀과 합의합니다.
- 구조 자체가 리뷰 대상입니다. 규칙에서 벗어난 배치는 PR 본문 "리뷰 포인트"에 근거를 남깁니다.

## 최상위 배치

루트 패키지는 `com.pinlog.pinlogback`입니다. 그 아래를 **도메인(feature) 축**과 **공통(global) 축** 둘로만 나눕니다.

```
com.pinlog.pinlogback
├─ PinlogBackApplication        # 부트스트랩 (단독 파일)
├─ domain/                      # 도메인별 세로 슬라이스
│  └─ {domain}/
│     ├─ controller            # HTTP 진입점 (@RestController)
│     ├─ service               # 도메인 로직·트랜잭션 경계
│     ├─ repository            # 영속성 (Spring Data JPA)
│     ├─ entity                # JPA Entity (@Entity)
│     └─ dto                   # 요청·응답 DTO
└─ global/                      # 도메인을 가로지르는 공통 관심사
   ├─ common                   # 공용 상수·enum·유틸·기반 타입 (BaseEntity 등)
   ├─ config                   # Spring 설정 클래스 (@Configuration)
   ├─ exception                # 공통 예외 정의·전역 핸들러
   ├─ response                 # 공통 응답 타입 (ApiResponse·ErrorResponse·CursorPage·Cursor)
   ├─ web                      # 서블릿·MVC 확장 (ResponseBodyAdvice·Filter)
   └─ security                 # 인증·인가. 지금은 인증 스텁(@LoginMember·MemberPrincipal·리졸버)만 있다
```

`response`와 `web`을 나눈 기준: `response`는 **직렬화되는 계약 타입**(응답 JSON의 형태 그 자체)이고, `web`은 그 계약을 **요청·응답 파이프라인에 적용하는 장치**입니다. 공통 응답 타입을 추가할 때는 `response`, advice·filter·interceptor·argument resolver는 `web`에 둡니다.

## 도메인 목록

각 도메인은 하나의 애그리거트를 담당하고, 위 5개 하위 계층(`controller`·`service`·`repository`·`entity`·`dto`)을 **필요한 것만** 만듭니다.

| 도메인 | 책임 | 비고 |
| --- | --- | --- |
| `member` | 회원 계정·프로필 | 공개 프로필·소개를 포함합니다. 별도 `profile` 도메인을 만들지 않습니다 |
| `place` | 장소 마스터 | |
| `record` | 방문·기록과 기록 본문(Context) | Context는 `record` 하위에 둡니다. 별도 `context` 도메인을 만들지 않습니다 |
| `collection` | 컬렉션·큐레이션 | |
| `follow` | 팔로우 관계 | |
| `feed` | 피드 조회·서빙 API | 관측 로그 `core.feed_event` 테이블은 **AI 소유(V102)** — 재정의 금지, 조회만 |
| `auth` | 인증·인가 | **별도 인증 PR에서 생성.** 그 전에는 만들지 않음 |
| `search` | 개인 자연어 검색 조회 API | Record를 돌려주지만 `record`에 두지 않습니다 — 진입 경로(`/v1/search/records`)와 조립 규칙(FastAPI 응답의 Core 재검증)이 Record CRUD와 다릅니다. FastAPI 호출 자체는 `ai`가 맡고 이 도메인은 그 결과를 검증·조립만 합니다 |
| `ai` | FastAPI AI Server 연동과 `ai` 스키마 접근 | 애그리거트가 아니라 **파트 경계**입니다. 하위 계층은 `repository`(백엔드가 `ai`에 쓰는 SQL과 응답 조립용 읽기) · `client`(내부 API 호출) · `event`(커밋 이후 훅) · `scheduler`(시간이 촉발하는 훅) · `service`(요청 조립·트랜잭션 경계) · `exception`(호출 실패를 도메인 오류로 옮김)이며, `controller`·`entity`는 없습니다 — 외부 진입점이 아니고 남의 스키마를 엔티티로 고정하지 않습니다. `event`와 `scheduler`를 가른 기준은 **무엇이 호출을 촉발하는가**이고, 그에 따라 트랜잭션 경계도 다릅니다 — `event`는 남의 트랜잭션이 커밋된 뒤에 얹히고, `scheduler`는 자기 트랜잭션을 열고 닫습니다(S15P11A705-159) |

> 검토 필요: `member` 행의 "공개 프로필·소개를 포함합니다"는 `docs/static/06_데이터모델_및_무결성.md` 2.1(익명 서비스이므로 저장하는 개인정보가 없다)과 실제 구현체(`domain/member/entity/Member` — 개인정보·프로필 컬럼 없음)에 모두 반합니다. CLAUDE.md 9번 규칙에 따라 임의로 고치지 않고 충돌로 기록만 남깁니다.
>
> 위 목록은 취소된 PR #8의 도메인 골격을 기준으로 정리하되, `profile`은 `member`에, `context`는 `record`에 통합했습니다. 경계가 커져 분리가 필요해지면 이 문서를 먼저 갱신하고 팀과 합의합니다.

## 계층 규칙

- `controller`는 `service`만 호출합니다. `repository`·`entity`를 직접 다루지 않습니다.
- Entity를 요청/응답으로 직접 노출하지 않습니다. 경계에서 `dto`로 변환합니다. 세부는 [API 규약](api-conventions.md)을 따릅니다.
- DTO가 늘어나면 도메인 `dto` 아래를 `dto/request`·`dto/response`로 나눌 수 있습니다. 나눌 때는 도메인 전체에 일관되게 적용합니다.
- 두 도메인이 함께 쓰는 코드는 어느 한 도메인에 두지 않고 `global/common`으로 올립니다. 다만 한 도메인에서만 쓰는 코드를 미리 `global`에 두지 않습니다.
- Flyway migration은 소스 패키지가 아니라 `src/main/resources/db/migration`에 두며, 버전 소유 구간은 [데이터베이스 규약](database-conventions.md)을 따릅니다.
- **소유자용과 공개용 DTO는 상속 없이 별개로 정의합니다.** 리포지토리 메서드명에도 `ForOwner` / `Public`을 명시합니다. 상속이나 조건부 직렬화는 부모에 필드가 추가될 때 조용히 개인정보를 노출합니다.

> 결정 배경: [BD-13](../backend/decisions/BD-13-public-boundary-query-dto-split.md) 공개 경계를 쿼리·DTO 분리로 강제한 이유 — 실수했을 때 유출이 아니라 컴파일 오류로 실패하게 만든다

## 인증·보안 경계

인증은 S15P11A705-63에서 [인증 PR 계약](authentication.md)에 따라 한 PR로 들어왔습니다. `global/security`는 **책임별 하위 패키지**로 나뉘어 있습니다.

| 패키지 | 책임 | 언제 도는가 |
| --- | --- | --- |
| `security/oauth` | 공급자와의 OAuth2 흐름 (OAuth 클라이언트 역할) | 로그인 진입·콜백 |
| `security/token` | 세션 토큰 서명·검증과 쿠키 전달 (BFF 역할) | 로그인 성공·재발급 |
| `security/authentication` | 요청을 인증 주체로 변환, principal 계약 (리소스 서버 역할) | 모든 요청 |
| `security/error` | 필터 체인이 직접 만드는 401·403 응답 | 인증·인가 실패 |

- **의존은 한 방향입니다.** `oauth`와 `authentication`이 `token`을 쓰고, `error`는 어느 쪽도 쓰지 않습니다. 반대 방향 참조가 생기면 경계가 잘못된 것이므로 클래스를 옮길 자리를 다시 봅니다.
- 발급(`token`)과 검증(`authentication`)을 가른 기준은 **수명과 호출 빈도**입니다. 발급은 로그인 시점에 한 번, 검증은 모든 요청에서 돕니다.
- **보안 설정은 `global/config`가 아니라 `global/security` 안에 둡니다.** 설정과 그 설정이 조립하는 구현이 떨어져 있으면 한쪽만 고치게 됩니다.
  - `security/SecurityConfig` — 하위 패키지 넷을 필터 체인으로 조립하는 유일한 지점이라 루트에 둡니다.
  - `security/token/JwtProperties` — 소비자(`JwtKeyProvider`·`JwtTokenProvider`·`AuthCookies`)와 같은 패키지입니다.
  - `security/authentication/LoginMemberArgumentResolverConfig` — `@LoginMember` 리졸버를 MVC에 등록합니다. `WebMvcConfigurer`는 여러 개가 공존할 수 있으므로, 일반 MVC 설정이 필요해지면 `global/config`에 따로 만들면 됩니다. 하나의 거대한 configurer로 모으지 않습니다.
- 어느 하위 패키지에도 속하지 않는 것은 `security` 루트에 둡니다(현재 `CsrfCookieFilter` 하나). **억지로 끼워 넣지 않습니다** — 이름이 거짓말이 되는 쪽이 더 비쌉니다.

> **하위 패키지에 클래스를 추가할 때**: `@NullMarked`는 **하위 패키지로 상속되지 않습니다.** 새 하위 패키지를 만들면 `package-info.java`에 직접 선언해야 하고, 빠뜨리면 컴파일은 통과하지만 `@Nullable` 표기가 조용히 무의미해집니다([BD-29](../backend/decisions/BD-29-nullmarked-security-package.md)).

## 테스트 패키지

테스트는 대상 클래스와 **같은 패키지 경로**를 `src/test/java`에 둡니다. DB가 필요한 테스트는 PostgreSQL Testcontainers를 사용합니다. 세부는 [테스트 규약](testing-conventions.md)을 따릅니다.

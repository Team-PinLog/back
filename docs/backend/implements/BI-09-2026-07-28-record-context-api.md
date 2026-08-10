# BI-09. Record·Context API와 인증 스텁, 지도 마커

- **상태**: ✅ 완료
- **날짜**: 2026-07-28
- **관련**: Jira 작업, back#28(인증 스텁 계약), [BD-12](../decisions/BD-12-duplicate-record-idempotent.md), [BD-14](../decisions/BD-14-identifier-concealment.md), [BD-07](../decisions/BD-07-context-immutability.md), [BD-25](../decisions/BD-25-context-origin-created-at.md)

## 산출

### 인증 스텁 (`global/security`)

- `MemberPrincipal(Long memberId)` record + `@LoginMember` + `LoginMemberArgumentResolver`(순수
  `HandlerMethodArgumentResolver`, Security 미도입). `WebMvcConfig`가 등록한다.
- `pinlog.auth.stub.enabled=true`(local·test만)일 때 `X-Debug-Member-Id` 헤더를 채택하고, 그 외에는
  항상 401(fail-closed). `ErrorCode.UNAUTHORIZED`(401) 신설.
- 테스트 인증은 `support/AuthTestSupport.loginAs(memberId)` 하나로 몰았다 — 인증 PR 후 교체 지점이
  리졸버 본문 + 헬퍼 본문 두 곳으로 고정된다.
- `package-structure.md`·`authentication.md`에 스텁 선생성과 principal 계약 선고정을 반영했다.

### Record·Context API (`domain/record`, `domain/place`)

- `POST /v1/records` — Place는 `INSERT ... ON CONFLICT (kakao_place_id) DO NOTHING` 후 재조회 upsert
  (`PlaceRepository.insertIfAbsent`). 기존 행을 전달값으로 갱신하지 않는다(스냅샷 원칙). 동일 Place에
  내 활성 Record가 있으면 Context 추가로 처리하고 200 `CONTEXT_ADDED`, 없으면 201 `RECORD_CREATED`
  (BD-12 — 서버가 판단하고 프론트에 거절을 반환하지 않는다).
- `GET /v1/records/{recordId}` — 소유자 상세. `contexts`는 `origin_created_at` 오름차순(동률 id
  오름차순), 타인 접근은 404로 은닉.
- `GET /v1/records/by-place?kakaoPlaceId=` — 내 활성 Record 조회. 없으면 `record: null`인 200
  (미저장 장소 조회는 정상 흐름).
- `POST /v1/records/{recordId}/contexts` — Context 추가(201) + `record.touch()`.
- `PATCH /v1/records/{recordId}/contexts/{contextId}` — 교체 수정. `findByIdForUpdate`
  (PESSIMISTIC_WRITE)로 Record를 잠근 뒤 **새 Context `saveAndFlush`가 먼저**, 구 Context
  `softDelete()`가 나중이다 — 활성 수가 1 → 2 → 1로 움직여 0이 되는 중간 상태가 없다.
  응답 `createdAt`은 구 Context의 `origin_created_at` 승계값이다(BD-25).
- `GET /v1/records/map` — bbox 없으면 전체, 있으면 범위 내 마커. `bounds`는 마커 전체의 최소 사각형
  (0개면 명시적 `null`, 1개면 sw=ne 점 사각형). bbox를 일부만 주면 400. 마커는 JPQL 생성자 표현식으로
  entity join(`Record` ↔ `Place`) 직조회 — 연관관계 없이 동작한다.
- `keywords`는 모든 응답에서 빈 배열로 미리 포함한다(명세 1.3 — AI 파트가 비동기로 채운다).
- `global/exception`에 `ResourceNotFoundException`(404 은닉)·`InvalidRequestException`(400) 추가.

## 검증

- `LoginMemberArgumentResolverTest` — 스텁 켜짐/꺼짐, 헤더 유무·형식의 401 계약.
- `RecordApiTests` 14건 — 생성 분기(201/200), Place 중복 미생성·스냅샷 유지, Context 정렬,
  타인 404, by-place null/존재, `updated_at` touch, 교체 수정의 id 교체·origin 승계·활성 1개 허용,
  401, 검증 400(공백 본문·위도 범위).
- `RecordMapApiTests` 6건 — 전체/최소 사각형, bbox 필터, 0개 null·1개 점, 타인 마커 제외, 부분 bbox 400.
- `./gradlew clean check --no-daemon` 통과.

## 참고

- Boot 4에서 `@AutoConfigureMockMvc`는 `org.springframework.boot.webmvc.test.autoconfigure` 패키지다
  (Jackson 3의 tools.jackson과 같은 계열의 이동).

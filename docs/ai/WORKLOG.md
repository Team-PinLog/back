# WORKLOG — Backend AI 파트

시간순 작업 로그입니다. 작업 기록이 유형별 폴더(spec/proposals/implements/troubleshooting)로 분산되어 있어, 한 작업의 전체 흐름을 추적하려면 여러 폴더를 오가야 합니다. 이 파일은 그 비용을 줄이기 위한 시간축 색인입니다. **이후 작업마다 항목을 하나씩 추가합니다.**

## 2026-07-23

- Spring AI 연동·Feed 추천 구현 명세 작성 (back#1) — [spec/](spec/)
- back docs README를 백엔드 공통 허브로 재구성 (back#2) — [../README.md](../README.md)
- Flyway 도입. `ai` 스키마·`feed_event` 마이그레이션 V1/V100~102 작성 (back#3) — [implements](implements/2026-07-23-flyway-ai-schema-migration.md), [P21](proposals/P21-flyway-migration-convention.md)·[P22](proposals/P22-feed-event-ownership.md)·[P24](proposals/P24-flyway-schemas-unspecified.md)
- 작업 기록 신설. 결정·트러블슈팅·리포트 폴더를 만들었다 (back#6) — [proposals/](proposals/)·[implements/](implements/)·[troubleshooting/](troubleshooting/)
- 문서 체계 재구조화. spec/proposals/implements/troubleshooting 구조와 WORKLOG를 도입하고, ADR 명칭을 P 번호로 바꿨다 (back#6) — 이 트리 전체

## 2026-07-28 — Feed 합의 반영 (S15P11A705-125)

back#58에서 이루어진 Feed 합의를 문서에 반영했다. 구현 담당은 김가현으로 정했다. MVP에서 category·region을 제외했다. 공용 API 계약(size 20·opaque cursor·별도 requestId)을 우선하기로 했고, 향후 placeMeta를 임베딩으로 전환할 조건을 기록했다. — [P42](proposals/P42-feed-mvp-without-place-metadata.md) · [Feed 명세](spec/feed-recommendation.md)

같은 날 담당을 긴급 정정했다(S15P11A705-125). Feed Spring 구현을 이정헌에게 재배치하고, 김가현은 Feed 범위에서 제외해 별도 추가 AI 기능 담당으로 분리했다. — [P42](proposals/P42-feed-mvp-without-place-metadata.md)

## 2026-07-28 — 최신 dev 기준 Feed 계약 재검토 (S15P11A705-125)

최신 `dev` 기준으로 Feed 계약을 재검토했다. 합의 당시보다 `dev`가 5커밋 앞서 있었다. 그중 S15P11A705-117이 "요청 입력의 서버 방어 상한은 이름 붙은 상수 한 곳(`InputLimits`)에 모으고 `CursorPage.MAX_SIZE`와 같은 값을 쓴다"는 규약을 세웠다.

Feed 명세의 `size=20`·opaque cursor는 공통 커서 계약(`DEFAULT_SIZE` 20 · `MAX_SIZE` 100 · `normalizeSize` 보정)과 이미 일치해 충돌이 없었다. 명세에 그 근거를 명시했다. 반면 `POST .../feed/events`의 배열 상한은 "예: 50"으로 미정이라 그 규약과 어긋났고, 100으로 고정했다.

경로 표기 불일치도 함께 정리했다. §4 코드블록만 `/api/core/v1/...`로 바뀌고, 본문·`feed-event.md`·`feed-tests.md`는 옛 경로 `POST /feed/events`로 남아 있던 상태였다. — [Feed 명세](spec/feed-recommendation.md) · [Feed 이벤트](spec/feed-event.md)

## 2026-07-29 — back#74 리뷰 반영 (S15P11A705-125)

`page-size`를 10에서 20으로 바꿀 때 탐색 슬롯 수가 따라가지 않아, 탐색 비중이 20%에서 10%로 절반이 된 것을 발견했다. 슬롯을 일반 2→4, Cold Start 3→6으로 올려 비율을 원복했다. 정책 변경이 아니라 누락 보정이다.

Place region 제거로 "AI 미완료 Collection이 점수를 얻는 경로"가 사라졌는데, 최신 채널을 60에서 100으로 늘린 것이 이 손실을 **부분적으로만** 보상한다는 점을 명시했다. Collection 쪽 Cold Start를 P42의 "감수하는 것" 목록에 추가했다.

수치 서술 두 곳을 바로잡았다. 후보 풀 상한 200과 채널 배분 합 200이 같아 잘라내기 규칙이 발동할 수 없다는 사실을 적었고, "10배 여유"로 적혀 있던 값의 실제치가 팔로우 0인 사용자 기준 6배 남짓임을 적었다. `InputLimits.FEED_EVENTS_MAX`라는 상수 이름과 상한 초과 시 `400 INVALID_INPUT` 응답을 못박았다. 이번에 고정한 API 계약 4가지를 `feed-tests.md`에 §11·E9로 추가했다. — [Feed 점수](spec/feed-scoring.md) · [Feed 테스트](spec/feed-tests.md) · [P42](proposals/P42-feed-mvp-without-place-metadata.md)

## 2026-07-29 — 내부 인증 헤더 표기 정정 (S15P11A705-96)

내부 인증 헤더 표기가 구현과 어긋난 것을 바로잡았다. 명세 §7이 `X-Internal-Token: <pinlog.ai.internal-token>`으로 적혀 있었으나, 실제 구현은 `ai/app/core/security.py`의 `INTERNAL_SECRET_HEADER = "X-Internal-Secret"`이고 테스트·E2E 도구도 전부 `X-Internal-Secret`을 쓴다. 플레이스홀더도 실제 주입 이름인 `INTERNAL_SHARED_SECRET`으로 맞췄다. 정정 전에는 이 문서만 보고 Spring을 붙이면 FastAPI가 401을 반환하는 상태였다. — [AI 연동 명세](spec/ai-integration.md)

## 2026-07-30 — 재스캔 명세 3.1의 근거 시나리오 정정 (S15P11A705-160)

재스캔 명세 3.1이 「Finalize를 먼저」의 근거로 든 시나리오가 실제로는 일어나지 않는다는 것이 back#104 구현 중 실측으로 드러났다. 두 단계를 맞바꿔도 테스트가 통과했다.

실제로 마지막 재시도의 창을 만드는 것은 `retry_count` 증가 시의 `updated_at` 갱신과 Finalizer 만료 조건이다. 스키마의 `DEFAULT now()`는 INSERT 기본값이라 UPDATE 시 자동 갱신되지 않는데도, 3.1 처리 순서에는 이 갱신이 빠져 있었다. 구현이 스스로 채워 넣은 장치가 명세에 없던 상태다.

3.1 순서 목록에 `updated_at` 갱신을 넣고, 근거를 이미 정확하던 6.1과 일치시켰다. 창을 만드는 것은 만료 조건과 `updated_at` 갱신이고, 순서는 심층 방어다. 순서 자체는 유지했다. 나중에 어느 구현이 `updated_at` 갱신을 빠뜨리면 그때 유일하게 남는 방어선이기 때문이다. 3.1·5·6.1이 서로를 참조하도록 연결했다. 구현 변경은 없다. — [재스캔 명세](spec/ai-rescan-scheduler.md)

## 2026-08-03 — Feed keywords를 display_name으로 교체 (back#146, S15P11A705-252)

Feed `keywords`가 `keyword_preset.code`를 내보내던 것을 `display_name`으로 교체했다(08 §6.1). 점수 계산(`FeedScorer.weightedJaccard`)의 키는 `code`로 유지하고 응답 조립 시점에만 매핑했다. `display_name`을 키로 쓰면 당장은 Jaccard가 그대로 성립해 테스트도 깨지지 않고 오류도 나지 않지만, 표시값이 바뀌는 순간 과거 Profile과의 매칭이 조용히 어긋나기 때문이다.

표시값을 못 찾은 code는 `code`로 대신 채우지 않고 응답에서 뺀다. 그 폴백 자체가 명세 위반이 되기 때문이다. N+1 부재는 `SqlQueryCounter`(신설)로 측정해 확인했고, 같은 카운터로 미검증 상태였던 feed-tests N4·N5도 함께 고정했다. back#145(조회 응답 3곳의 빈 keywords)는 이미 CLOSED였고 `ContextKeywordRepository`(별도 신설)로 처리돼 있어 `code`를 내보내지 않는 것을 확인했다. — [implements](implements/2026-08-03-feed-keyword-display-name.md)

## 2026-08-03 — 검색 응답 상태 노출을 금지하던 명세 §5 개정 (back#136, S15P11A705-209)

검색 응답이 「분석 중」·「0건 완료」·「실패」를 구분하지 못하는 문제를 응답 조립 명세 §5 개정으로 풀었다. 직전 판까지 §5는 「상태 필드를 노출하지 않는다」·「구분할 필요도 없다」였고, 그 근거는 **처리가 짧게 끝난다는 가정**이었다. 실측이 그 가정을 벗어났다. -121·-197에서 GMS 판정이 분당 2건이었고, -198에서 PROCESSING 잔류로 10분 결빙이 관측됐다.

뒤집은 것은 그 두 줄뿐이다. 빈 배열 유지·`null` 금지는 그대로다. 이전 판단의 비용 근거(「모든 클라이언트가 3상태를 분기해야 한다」)가 아직 유효하다고 보아, 노출을 **검색 응답 하나로 좁히고** 필드를 무시하면 개정 전과 동일하게 동작하도록 설계했다.

내부 5값을 응답 3값으로 접는다. `PENDING`은 `PROCESSING`으로 합류하고, `CANCELLED`는 집계에서 제외하며, 상태 행이 없으면 `COMPLETED`로 본다. 사용자에게 필요한 판단이 「기다리면 오는가」 하나이기 때문이다. 행 없음을 `PROCESSING`으로 접으면, 영영 오지 않는 것에 「분석 중」을 띄우는 — 바로 이 개정이 고치려던 — 증상이 재발한다. 타인 응답에 넣지 않은 것은 남의 처리 진행 상황이 새기 때문이다(§2 Visibility). 구현은 후속 작업으로 남겼다. — [응답 조립 명세 §5](spec/ai-response-assembly.md)

## 2026-08-03 — 검색 응답에 keywordStatus 구현 (back#136, S15P11A705-209)

검색 응답에 Record 단위 `keywordStatus`를 노출했다(명세 §5.1 개정분). **기존 세 Keyword 쿼리는 한 글자도 바꾸지 않았다.** 그 쿼리들은 `keyword_status = 'COMPLETED'` INNER JOIN이라 미완료 Context를 애초에 만나지 못해, 상태 집계를 같은 쿼리로 합칠 수 없다. 합치려고 조건을 풀면 `keywords` 배열의 계약이 바뀐다. 그래서 `LEFT JOIN` 집계를 별도 쿼리로 두고 쿼리 1회 증가를 택했다. 하위 호환 위험을 코드 구조로 없애는 값이 왕복 1회보다 크다고 판단했다.

`CASE`의 분기 순서가 상태 접기 규칙이며, `PROCESSING`이 `FAILED`를 이긴다. 처리 중인 것이 끝나면 Keyword가 더 붙을 수 있기 때문이다. 조립 단계에서 `keywords`와 `keywordStatus`를 서로 맞추지 않는다. 두 값이 다른 쿼리에서 오고 그 사이 판정이 끝날 수 있어, 맞추면 둘 중 하나를 조용히 거짓으로 만들기 때문이다.

하위 호환 검증에서 「기존 쿼리 무변경」은 코드 읽기로만 확인되는 절반이다. 그래서 **미완료 Context가 섞인 Record에서 배열이 그대로인지를 실행으로** 붙잡았고, 기존 36건이 그대로 통과하는 것이 나머지 절반이다. N+1 부재는 `SqlQueryCounter`로 측정했다. 결과가 3→12건으로 늘어도 상태 조회는 1회로 고정된다(-252 선례). 공용 계약 반영(docs#41)이 구현보다 먼저였다. 백엔드가 직렬화 테스트를 문서 근거로 고정할 수 있어야 한다는 요청이다. — [implements I17](implements/2026-08-03-search-keyword-status.md) · [응답 조립 명세 §5.1](spec/ai-response-assembly.md)

## 2026-08-03 — Feed keywords 상한 3과 동점 규칙 (ai#93, S15P11A705-278)

Feed 응답 `keywords`에 상한이 없어 프론트(front#88 책장 레이아웃)가 카드를 확정하지 못하던 것을 풀었다. **개수 3과 빈도 내림차순은 프론트와의 구두 합의(2026-08-03)이고, 이 작업의 결정은 동점 규칙 하나다.** 명세에 출처 표를 두어 합의분과 결정분을 갈라 적었다. 개수를 데이터로 역산하지 않는다. 초판에서 「4 = 프리셋 축 수」로 적었던 것을 걷어냈다. 상한은 같은 날 4에서 3으로 한 번 더 바뀌었고, 그때 움직인 것도 화면 쪽이지 측정값이 아니었다.

티켓의 전제 하나가 사실과 달랐다. 현재 순서는 `GROUP BY` 결과가 아니라 `.sorted()`가 건 표시값 가나다순이었고 이미 결정적이었다. 진짜 문제는 `display_name`을 한 글자 고치면 카드 구성이 통째로 바뀐다는 것이었다(RED 테스트에서 세 자리 모두 이동).

현 구현·상한 3 기준 실측에서, 16건 중 10건(63%)이 모든 Keyword의 빈도가 1이고, 자를 필요가 있는 12건 중 11건(92%)이 3위·4위 동점이다. 즉 빈도 내림차순은 잘리는 자리를 대부분 가르지 못하고, 동점 규칙이 화면을 정한다. 그래서 `preset.id`(불변·UNIQUE) 앞에 축 내 순위를 두어 동점 무리 안에서 네 축을 한 바퀴 돌린다. `preset.id` 단독 대비 축 커버리지가 16건 중 5건 증가했고 감소는 0건이다. `display_name` 사전순은 라벨이 순서를 움직여 기각했고, 최근 부여순은 시각 컬럼이 없어 기각했으며, `GROUP BY` 순서는 DB가 보장하지 않아 후보에서 제외했다.

**축은 같은 빈도 안에서만 일한다.** 초판이 축을 전역 1순위에 둬 빈도 5·4·1을 [5, 1, 4]로 뒤집었고, 합의된 정렬과 어긋나 되돌렸다. 그 회귀는 빈도가 전부 같은 데이터에서는 두 구현의 결과가 같아 기존 테스트로는 걸리지 않았고, `frequencyOutranksAxisSpread` 테스트를 따로 세웠다.

이 작업이 남긴 교훈: 수치를 옮길 때 「이 수치를 만든 조건이 지금도 같은가」를 물어야 한다. 축 커버리지 수치를 조건이 두 번 바뀐 뒤에도 재사용했고, 검증이 그것을 잡았다.

표시 정렬은 추천 점수와 가른다. `keywordAffinity`는 보는 사람 기준이라 남의 카드에 내 Profile이 비치기 때문이다(ai#92와 정책 공간 분리). 08 §10.1 반영은 별도 레포라 후속 작업이다. — [P46](proposals/P46-feed-keyword-display-order.md) · [implements I18](implements/2026-08-03-feed-keyword-display-order.md) · [Feed 명세 3.7.1](spec/feed-recommendation.md)

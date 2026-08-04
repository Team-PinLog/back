# 피드 후보 채널의 정렬키 표현식을 걷어냈다(S15P11A705-303)

- **날짜**: 2026-08-04
- **추적**: S15P11A705-303
- **관련**: [BI-38](../implements/BI-38-2026-08-03-massive-scale-plan-observation.md)(근거) · [BD-33](../decisions/BD-33-published-at-invariant.md)

BI-38이 1순위로 지목한 것을 고쳤다. `FeedCandidateRepository`의 최신·팔로우 채널이 `ORDER BY COALESCE(published_at, created_at)`를 써서 `ix_collection_feed (is_published, published_at DESC)`의 정렬 순서를 쓸 수 없었고, 상위 100건만 필요한데도 활성 발행 66만 행 전부를 Sort로 넘기고 있었다. 정렬키를 `c.published_at DESC, c.id DESC`로 바꾸고 SELECT의 `COALESCE`도 네 쿼리에서 모두 걷어냈다 — `is_published = true` 필터 안에서는 V5 `ck_collection_published_at` CHECK가 NOT NULL을 보장하므로(BD-33) 감쌀 대상이 없고, 따라서 `FeedScorer.recency()`와 `ScoredCandidate` 타이브레이커가 받는 값도 그대로다.

**같은 실행·같은 캐시 조건에서 나란히 쟀다**(천만 건 볼륨, 1Gi 한도, 콜드 재기동): 현재 코드 **2.30ms**(Index Scan `ix_collection_feed`, 105행, 디스크 89블록) 대 옛 형태 **236.65ms**(Parallel Seq Scan, 664,689행, 디스크 9,960블록) — **103배**. 실제 API도 괴물 회원(record 20,000) 기준 피드가 **368ms → 197ms**로 줄었다(중앙값 10회, 최소 105ms).

판단 셋. ① **테스트가 SQL 사본을 들지 않는다** — `FeedChannelPlanTests`가 리포지터리 상수를 그대로 EXPLAIN한다. 사본을 들면 본체만 바뀌었을 때 통과해 거짓 안심을 준다. 그래서 두 상수를 package-private으로 열었고 그 가시성의 유일한 이유를 주석에 적었다. ② **시간이 아니라 계획을 단정한다** — Testcontainers DB는 행이 적어 Seq Scan이 실제로 더 싸므로 시간으로는 개선 전후가 갈리지 않는다. `enable_seqscan = off`로 인덱스 경로를 후보에 올린 뒤 `Presorted Key`의 유무를 본다. 이 판정은 표 크기와 무관하다. ③ **표현식을 되돌리면 깨지는 것까지 같은 자리에서 보인다** — 상수를 복사해 COALESCE를 끼운 변형이 `Presorted Key`를 잃는 것을 단정하고, 치환이 no-op이 되면 그것도 실패하게 가드를 뒀다. RED 확인: 옛 형태로 임시 되돌려 인덱스 순서 단정 2건이 실패하는 것을 먼저 봤다.

측정 중 함정 둘. 앱이 죽은 상태에서 잰 "2.2초"는 응답 시간이 아니라 curl 연결 실패 시간이었다(종료 코드 7) — 폐기하고 수정된 jar로 다시 띄워 쟀다. 그리고 postgres만 재기동하면 Redis가 빠져 health가 DOWN으로 남는다.

**남은 197ms의 정체를 찾았다 — `PROFILE_KEYWORDS_SQL`이 163ms다.** `ai.context_keyword`(213,290행)를 Index Only Scan으로 전수 훑고 Merge Join한다. 벤치 데이터에 AI 파생 행이 없어 결과가 0건인데도 그렇다. 이 쿼리는 이 티켓 범위 밖이고 Feed 추천·AI 클라이언트 경계에 걸리므로 후속 후보로만 남긴다 (S15P11A705-303)

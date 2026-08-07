# Record가 담긴 내 Collection 목록 조회 API를 쿼리 수 실측과 함께 마감했다

- **날짜**: 2026-08-07
- **추적**: S15P11A705-391
- **관련**: [BI-43](../implements/BI-43-2026-08-07-record-collections-api.md) ·
  `d8d0514`(Task 1~3) · [BI-38](../implements/BI-38-2026-08-03-massive-scale-plan-observation.md)

`GET /v1/records/{recordId}/collections`(명세 §5.10)의 마지막 태스크. 앞선 세 태스크가
첫 페이지·정렬 방향·커서 페이징을 순서대로 얹었고, 이번 태스크는 "항목이 늘어도 쿼리는
그대로다"를 코드 읽기가 아니라 측정으로 못박고 실행 계획을 기록에 남겼다.

`SqlQueryCounter`로 Collection 2건짜리 Record와 8건짜리 Record를 같은 방식으로 조회해
`ai.context_keyword` 조회가 두 경우 모두 정확히 1회임을 확인했다. Collection 페이지 조회와
소유 확인용 Record 조회도 각각 1회로 고정된다 — 세 쿼리 다.

실행 계획은 로컬 dev DB(`pinlog-postgres` 컨테이너)가 비어 있어 `BEGIN`/`ROLLBACK`으로 감싼
합성 데이터(회원 1·collection 300·collection당 record 2건)로 재현했다. 처음에는 collection당
record를 1건만 연결했다가, `exists` 서브쿼리가 `ix_colrec_collection`으로 풀려 브리프가
예상한 두 후보(`uq_colrec_active`/`ix_colrec_record`) 어느 쪽도 아닌 결과가 나왔다. 한
Collection에 Record가 보통 여러 개 담기는 실제 모양과 다르다고 판단해 collection당 2건으로
다시 만들었고, 그러자 `uq_colrec_active`의 Index Only Scan으로 계획이 바뀌었다 — 이 인덱스는
원래 "Collection 내 동일 Record 중복 금지" 무결성 제약으로 있던 것이라, 조회가 그 제약에
무임승차한다. 실측 세부와 `EXPLAIN (ANALYZE, BUFFERS)` 원문은 BI-43에 있다.

로컬 dev DB의 `core.member`/`place`/`record` identity 시퀀스가 기존 loadtest 시드보다 뒤처져
있어(예: 다음 identity 값이 1인데 id=1이 이미 존재) 그대로 삽입하면 충돌한다는 것도 이번에
확인했다. 시퀀스 자체를 고치는 대신 실제 max(id)와 겹치지 않는 고정 오프셋에 명시적 id를
`OVERRIDING SYSTEM VALUE`로 지정해 우회했다 — 시퀀스 드리프트를 고치는 것은 이 태스크의
범위 밖이라 별도로 제기하지 않고 관측만 남긴다.

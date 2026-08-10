# 계획 단정 테스트의 플레이크를 닫고 잘못된 단정을 바로잡았다

- **날짜**: 2026-08-05
- **관련**: [BT-07](../troubleshooting/BT-07-planner-knobs-insufficient-for-plan-assertion.md) · Jira 작업 · [BI-38](../implements/BI-38-2026-08-03-massive-scale-plan-observation.md)

`FeedChannelPlanTests`가 공유 테스트 DB 적재량에 따라 흔들린다는 보고를 받고 재현했다. 회원과 Collection을 1:1로 늘려가며 계획을 보니 **20~100행 구간에서만 실패**했다 — 그 구간에서 플래너가 `ix_collection_member`로 Merge Join을 골라 `ix_collection_feed`를 타지 않고, 그러면 정렬 순서를 얻지 못해 전체 Sort가 붙어 `Presorted Key`가 사라진다. 정렬키는 올바른데 테스트만 깨지는 거짓 실패였다. **스캔만 막고 조인 방식을 막지 않은 것이 원인**이므로 `enable_mergejoin`·`enable_hashjoin`을 함께 끄고, 실패했던 적재량을 픽스처로 고정하는 테스트를 더했다(0~1만 행 전 구간 통과, 표현식 정렬키는 여전히 걸러짐). 조사 중 **더 큰 문제를 찾았다 — 같은 테스트가 팔로우 채널에도 `Presorted Key`를 요구하고 있었고 그것은 채널의 성질을 잘못 본 단정이다.** 팔로우 채널은 `follow`에서 출발해 `followee_member_id`로 좁히므로 올바른 인덱스가 `ix_collection_member`이고 `ix_collection_feed`를 쓸 이유가 없다(천만 건 실측 0.99ms). 데이터 형태에 따라 우연히 통과하고 있었을 뿐이라, 정렬 순서 대신 "전체 스캔으로 새지 않는지"를 단정하도록 바꿨다. 같은 이유로 303이 "팔로우 채널도 고쳤다"로 읽히게 쓴 서술도 정정했다 — 그쪽 `COALESCE` 제거는 일관성 정리이며 성능 개선이 아니다. 교훈은 BT-07에 남겼다: 계획을 단정하려면 대안 경로를 전부 막아야 하고, 플레이크는 실패 조건을 픽스처로 고정해야 닫히며, 우연히 통과하는 단정은 없는 것보다 나쁘다

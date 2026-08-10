# BT-07. 계획 단정 테스트가 조인 방식 때문에 흔들렸다

- **상태**: ✅ 해소
- **날짜**: 2026-08-05
- **관련**: Jira 작업([BI-38](../implements/BI-38-2026-08-03-massive-scale-plan-observation.md) 개선 후보 1의 구현)

## 증상

`FeedChannelPlanTests`가 공유 테스트 DB의 적재량에 따라 붙었다 떨어졌다 했다. 정렬키는 올바른데
(`ORDER BY c.published_at DESC`) `Presorted Key`가 계획에 없다는 이유로 실패한다.

전체 스위트가 통과할 때도 있고 특정 조합에서만 깨지므로, 실패를 봤을 때 "코드가 잘못됐나"를
먼저 의심하게 된다 — 실제로는 코드가 아니라 테스트가 틀렸다.

## 재현

행 수를 바꿔가며 같은 쿼리의 계획을 봤다. 회원과 Collection을 1:1로 늘렸고 스캔만 껐다
(`enable_seqscan = off`, `enable_bitmapscan = off`).

| collection 행 수 | `Presorted Key` | 정렬 노드 | 쓴 인덱스 |
| --- | --- | --- | --- |
| 0 · 1 · 5 | 있음 | Incremental Sort | `ix_collection_feed` |
| **20 · 100** | **없음** | **Sort** | **`ix_collection_member`** |
| 500 · 2,000 · 10,000 | 있음 | Incremental Sort | `ix_collection_feed` |

## 원인

**스캔만 막고 조인 방식을 막지 않았다.** 20~100행 구간에서 플래너는 `ix_collection_member`로
**Merge Join**을 고르는데, 그러면 `ix_collection_feed`를 타지 않아 정렬 순서를 얻지 못하고
전체 Sort가 붙는다. 20행 계획:

```
Limit
  -> Sort  Sort Key: c.published_at DESC, c.id DESC
     -> Merge Join  Merge Cond: (c.member_id = m.id)
        -> Index Scan using ix_collection_member on collection c
        -> Index Only Scan using ix_member_active on member m
```

`enable_seqscan`·`enable_bitmapscan`은 **스캔 방식**만 제한한다. 조인 방식이 바뀌면 플래너는
다른 인덱스로 새어 나갈 수 있고, 그러면 정렬 순서 판정이 뒤집힌다.

## 해소

`enable_mergejoin`·`enable_hashjoin`까지 함께 끈다. 그러면 남는 경로가 Nested Loop +
순서를 보존하는 Index Scan뿐이라 0행부터 1만 행까지 전 구간에서 `ix_collection_feed`를 탄다.
표현식 정렬키일 때는 여전히 `Presorted Key`가 없으므로 **판별력은 유지된다**(확인함).

실패했던 적재량(Collection 60행, 회원 1:1)을 직접 만들어 놓고 단정하는 테스트를 추가했다 —
공유 DB의 우연한 적재량에 기대지 않도록.

## 함께 바로잡은 것 — 잘못된 단정

같은 테스트가 **팔로우 채널에도 `Presorted Key`를 요구하고 있었다. 그것은 채널의 성질을 잘못 본
것이다.** 팔로우 채널은 `follow`에서 출발해 `followee_member_id`로 Collection을 찾으므로 발행
전체를 훑지 않고, 올바른 인덱스는 `ix_collection_member`다. 마지막 정렬은 팔로우한 사람의
Collection 수만큼이라 싸다(천만 건 실측 0.99ms).

이 단정은 데이터 형태에 따라 우연히 통과하고 있었을 뿐이다. **팔로우 채널에서 볼 것은 정렬
순서가 아니라 전체 스캔으로 새지 않는지**이므로, follow를 인덱스로 짚고 Collection을 회원
단위로 좁히는지를 단정하도록 바꿨다.

같은 이유로 Jira 작업이 "팔로우 채널도 고쳤다"고 읽히게 쓴 서술도 정정했다 — 그 채널의
`COALESCE` 제거는 일관성을 위한 정리이며 성능 개선이 아니다.

## 교훈

- **계획을 단정하려면 대안 경로를 전부 막아야 한다.** 스캔만 막는 것으로는 부족하다.
  플래너 노브를 부분만 끄면 "막지 않은 차원"으로 새어 나가고, 그 결과가 데이터 크기에 따라
  달라져 플레이크가 된다.
- **플레이크는 실패한 조건을 픽스처로 고정해야 닫힌다.** 한 번 통과한 것으로 해소를 판단하면
  같은 자리에서 다시 난다.
- **단정 대상이 그 쿼리에 맞는지 먼저 확인한다.** 두 채널이 같은 인덱스를 쓸 것이라고 가정했다가
  틀렸다. 우연히 통과하는 단정은 없는 것보다 나쁘다 — 틀린 확신을 준다.

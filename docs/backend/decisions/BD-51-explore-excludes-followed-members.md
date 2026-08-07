# BD-51. 탐색 탭은 이미 팔로우한 회원의 Collection을 제외한다

- **상태**: Accepted
- **날짜**: 2026-08-07
- **관련**: AI 파트 소유 명세 [feed-scoring](../../ai/spec/feed-scoring.md) 2.1·2.3·3.1,
  [feed-recommendation](../../ai/spec/feed-recommendation.md) 3.2 — 이 결정이 상충 사실을 마킹한 자리
- **번호**: [worklog README](../worklog/README.md)·[BD-45](BD-45-worklog-per-entry-files.md)의 규칙대로 `dev` 머지 순서가 번호를 확정한다. 이 문서는 git 저장소가 아직 초기화되지 않은 로컬 작업 중 작성됐으므로, 머지 시점에 51이 이미 다른 결정에 쓰였다면 [BD-50](BD-50-datasource-redis-config-follows-infra-env-vars.md)의 선례대로 번호만 옮기고 내용은 그대로 둔다.

## 맥락

사용자 요청: 탐색(`GET /v1/feed/collections`) 탭에서 **이미 팔로우 중인 회원의 Collection이 뜨지 않게** 해 달라. 신규 발견이 탐색 탭의 목적이므로, 이미 관계를 맺은 사람의 책은 팔로잉 화면 쪽에서 보면 되고 탐색에서는 새 얼굴을 보고 싶다는 취지다.

코드를 확인하니 이것은 버그가 아니라 **AI 파트 소유 명세가 정반대로 설계해 둔 핵심 로직**이었다.

- `feed-scoring.md` 2.1의 후보 채널 표는 "팔로우"를 "이미 관심을 표현한 Shelf"로 적어 최신 발행 다음으로 큰 배분(80)을 준다.
- `feed-scoring.md` 3.1·3.5는 `followSignal`(팔로우 채널 출처 여부)을 점수 공식의 첫 항으로 두고, `w_follow = 0.500`으로 **세 가중치 중 가장 크게** 잡는다. 근거는 "팔로우가 사용자가 명시적으로 표현한 유일한 관심 신호이기 때문 — 추론값보다 명시값을 우선한다."
- `FeedProperties.java`의 클래스 Javadoc은 "값의 정본은 명세이고 여기서 임의로 바꾸지 않는다 — 어긋나 보이면 AI 파트에 올린다"고 명시한다.

즉 지금 코드(`FeedCandidateRepository.findFollowed`, `FeedScorer`의 `followSignal`)는 이 명세를 정확히 구현한 것이다. 사용자가 원하는 동작은 이 설계 의도를 **정면으로 뒤집는다.**

`back/CLAUDE.md` 규칙 9는 "코드와 문서가 충돌하면 스스로 풀지 말고 정본 쪽에 마킹만 남기라"고 한다. 이 사안은 코드-문서 불일치가 아니라 **제품 요구사항이 기존 명세의 설계를 뒤집는 경우**이지만, 같은 원칙을 적용해 AI 파트 명세는 고치지 않고 상충 사실만 마킹했다({@code feed-scoring.md} 2.1·3.1, {@code feed-recommendation.md} 3.2의 `[BD-51 상충]` 표시). 명세의 값 자체(`w_follow`, `follow-limit` 등)도 지우지 않았다 — AI 파트 소유이며, 되돌릴 때 근거가 남아 있어야 한다.

## 선택지

| 안 | 장점 | 단점 |
|---|---|---|
| (a) 아무것도 바꾸지 않고 사용자에게 "명세와 상충한다"만 알린다 | 명세와의 정합이 깨지지 않는다 | 사용자가 명시적으로 원하는 동작을 구현하지 않는 것이 된다 |
| (b) `findFollowed` 채널·`FeedScorer`의 `followSignal`·`FeedProperties`의 `wFollow`/`followLimit`을 코드에서 완전히 지운다 | 죽은 코드가 남지 않는다 | AI 파트 소유 값·구조를 백엔드가 임의로 삭제하는 것이라 명세 재정비 없이는 되돌리기 어렵고, `FeedCandidateChannelTests`의 C6·C8(팔로우 채널 자체의 동작 검증)도 함께 지워야 해 팔로우 채널 인프라 자체의 회귀 방지가 없어진다 |
| **(c) 탐색 파이프라인(`FeedService.collectCandidates`)이 `findFollowed` 채널 호출을 멈추고, 남은 두 채널(`RECENT_SQL`·`SAMPLE_*_SQL`)의 WHERE 절에 `NOT EXISTS(core.follow ...)`로 팔로우한 회원을 직접 제외한다. `FOLLOWED_SQL`·`findFollowed`·`wFollow`·`followLimit`은 남긴다** | 명세가 소유한 값·SQL·테스트(C6·C8, `FeedChannelPlanTests`)를 건드리지 않아 되돌리기 쉽다. `followSignal`이 항상 0이 되어 사실상 무력화되면서도 삭제로 인한 컴파일 연쇄가 없다 | `wFollow`·`followLimit`이 당분간 아무 데서도 읽히지 않는 설정값으로 남는다 — 죽은 설정처럼 보일 수 있다 |

## 결정

**(c)를 골랐다. 능동적 선택이다.**

1. **명세 값은 AI 파트 소유이므로 백엔드가 구조까지 지우지 않는다.** `FeedProperties` Javadoc이 이미 "어긋나 보이면 AI 파트에 올린다"고 명시하는데, (b)처럼 레코드 필드·SQL·전용 테스트를 지우면 그 경로 자체가 없어져 AI 파트와 재조율할 근거가 사라진다.
2. **행동은 확실히 바뀌어야 한다.** 단순히 `wFollow`를 설정에서 0으로 낮추는 것만으로는 부족하다 — 그러면 팔로우한 회원의 Collection이 여전히 최신/무작위 채널을 통해 후보에 들어와 노출될 수 있다(팔로우 여부와 무관하게 최신순·무작위 채널은 원래 모든 공개 Collection을 대상으로 하므로). 그래서 남은 두 채널의 WHERE 절에 **명시적 제외 조건**을 추가했다 — 이 저장소의 다른 노출 조건(활성·발행·소유자 미탈퇴 등)과 같은 자리, 같은 방식이다(feed-recommendation 3.3 규약).
3. **팔로우 채널 인프라 자체는 살아 있는 게 맞다.** `FOLLOWED_SQL`은 `core.follow`를 기점으로 좁히는 올바른 쿼리 계획(`ix_collection_member`)이 이미 검증돼 있고(`FeedChannelPlanTests`), 이 자산을 지울 이유가 없다 — 지금은 탐색 파이프라인이 부르지 않을 뿐이다.

## 결과

- **이 결정으로 감수하는 것**
  - `FeedProperties.Candidate.followLimit()`과 `FeedProperties.Scoring.wFollow()`/`ColdStart.wFollow()`가 당분간 어떤 프로덕션 코드에서도 읽히지 않는다. `application.yml`의 `follow-limit`·`w-follow` 값도 마찬가지다. 죽은 설정처럼 보이지만, 명세 재조율 시 되살릴 자리를 남겨 두기 위한 의도적 선택이다.
  - `FeedCandidateRepository.findFollowed`/`FOLLOWED_SQL`도 프로덕션 호출자가 없다 — 오직 `FeedCandidateChannelTests`(C6·C8)와 `FeedChannelPlanTests`만 직접 부른다. 이 테스트들은 "팔로우 채널 쿼리 자체는 여전히 올바르다"만 검증하고, "탐색 응답에 반영되는지"는 더 이상 보장하지 않는다.
  - 탐색 탭에서 팔로우한 회원의 Collection은 어떤 경로로도 다시 나타나지 않는다. 팔로우한 사람의 새 책을 보려면 별도 화면(팔로잉 Shelf 등)이 필요하며, 이 저장소 범위에는 없다.
  - Cold Start 가중치 표(`w_follow = 0.500`)도 명세상 그대로지만 실질적으로 무의미해졌다 — Cold Start 사용자도 팔로우한 회원의 Collection을 후보로 받지 못하므로 `followSignal`이 항상 0이다.
- **재검토 트리거**
  - AI 파트가 `feed-scoring.md`·`feed-recommendation.md`를 이 결정에 맞춰 갱신하면(또는 반대로 원래 설계를 재확인하면), 그 갱신에 맞춰 `wFollow`·`followLimit`을 코드에서 실제로 정리(삭제 또는 별도 "팔로잉" 채널로 전환)한다.
  - 팔로우한 회원의 새 책을 보여줄 별도 화면(팔로잉 피드)이 생기면, `findFollowed`/`FOLLOWED_SQL`을 그 화면의 데이터 소스로 재사용할 수 있다 — 지금 지우지 않은 이유이기도 하다.

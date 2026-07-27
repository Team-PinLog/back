# BD-17. Keyword는 프리셋에서만 — 3등급 공개, 상위 집계는 저장하지 않음

- **상태**: Accepted
- **날짜**: 2026-07-22 (AI 설계·데이터 모델 확립 시점. 단일 커밋으로 특정 불가)
- **기록**: 소급 (2026-07-27 작성)
- **관련**: S15P11A705-76
- **공용 계약**: [02_정책_정의서 §6](https://github.com/Team-PinLog/docs/blob/main/static/02_정책_정의서.md) · [06_데이터모델_및_무결성 §2.9·§5.4](https://github.com/Team-PinLog/docs/blob/main/static/06_데이터모델_및_무결성.md) · [05_AI_설계 §4.4](https://github.com/Team-PinLog/docs/blob/main/static/05_AI_설계.md)

## 맥락

Keyword는 타인에게 공개되는 유일한 Context 파생물이다. Context 본문은 절대 공개되지 않지만([BD-12](BD-12-public-boundary-query-dto-split.md)), Keyword는 Feed와 공개 Collection에 노출된다.

**즉 Keyword를 공개한다는 것은 Context의 일부가 마스킹된 형태로 공개된다는 뜻이다.** LLM이 Keyword를 자유롭게 생성하면 사람 이름, 구체적 회사·학교, 정확한 일정, 식별 가능한 사건이 그대로 나갈 수 있다.

여기에 저장 구조 문제가 겹친다. Keyword를 Context 단위로 둘 것인가 Record 단위로 둘 것인가.

## 선택지

**생성 방식**

| 안 | 장점 | 단점 |
|---|---|---|
| (a) LLM이 자유 생성 | 표현이 풍부하고 맥락에 정확히 맞음 | 공개 안전성을 보장할 방법이 없다. 프롬프트로 막는 것은 확률적이다 |
| **(b) 사전 정의 프리셋에서만 선택** | 공개되는 값의 집합이 유한하고 사람이 검수 가능 | 프리셋에 없는 표현은 붙일 수 없다. 매칭 0개가 자주 날 수 있다 |

**저장 단위**

| 안 | 장점 | 단점 |
|---|---|---|
| (c) Record 단위 저장 | 조회 시 조인이 적다 | Context 하나를 지울 때 **어느 Keyword를 제거할지 판별할 수 없다.** Record 전체 재분석이 필요해진다 |
| **(d) Context 단위 저장 + 상위는 읽기 집계** | 삭제 영향 범위가 해당 Context로 한정된다 | Record·Collection Keyword를 볼 때마다 조인 집계 비용 |

## 결정

**(b)와 (d)를 채택한다. 능동적 선택.**

- **Keyword는 `ai.keyword_preset`의 사전 정의 목록에서만 선택한다.** AI가 목록 밖 코드를 반환하면 매핑 단계에서 폐기한다. **이 제약이 공개 Keyword 안전성의 전제 전체다.**
- **공개 등급은 셋이다.** `PUBLIC`은 타인에게, `PRIVATE_ONLY`는 본인에게만, `BLOCKED`는 본인에게도 제공하지 않는다.
- **원본은 Context Keyword다.** Record Keyword와 Collection Keyword는 저장하지 않고 읽기 시점 조인 집계로 만든다.
- 사용자는 Keyword를 **조회만** 한다. 직접 생성·수정·삭제할 수 없다.
- 매칭되는 Keyword가 하나도 없을 수 있고 **이는 오류가 아니다.** AI 분석이 미완료·실패해도 Keyword만 생략하고 Place·Record·Collection은 정상 제공한다.

## 결과

**감수하는 것**

- **표현력 상한** — 프리셋에 없는 뉘앙스는 영영 붙지 않는다. 프리셋 확장은 임베딩 재생성을 동반하므로 가벼운 작업이 아니다.
- **빈 Keyword가 흔하다** — 매칭 0개, 분석 미완료, 분석 실패 세 경우 모두 화면이 정상으로 취급해야 한다.
- **집계 비용** — Feed와 Collection 상세에서 Context Keyword를 매번 조인·집계한다. `record_id IN (...)`으로 일괄 조회 후 애플리케이션에서 그룹핑한다.
- **프리셋 버전 관리** — `preset_version`과 `embedding_profile`이 붙어 있어, 프리셋이나 모델이 바뀌면 재판정 대상 판별이 필요하다.

**재검토 트리거**

- **자유 생성으로 바꾸려는 시도가 나오면 이 문서를 먼저 본다.** 그 순간 [BD-12](BD-12-public-boundary-query-dto-split.md)의 공개 경계 전제가 함께 무너진다. 두 결정은 반드시 같이 재검토한다.
- Feed 성능이 집계 때문에 문제가 되면 → Collection Keyword 읽기 전용 캐시를 추가한다. 원본을 Record 단위로 옮기지는 않는다. (c)를 버린 이유가 그대로 유효하기 때문이다.

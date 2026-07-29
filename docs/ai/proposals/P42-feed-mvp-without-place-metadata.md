# P42: MVP Feed에서 Place category·region을 제외한다

- **상태**: Accepted
- **날짜**: 2026-07-28
- **관련**: [back#58](https://github.com/Team-PinLog/back/issues/58), S15P11A705-125,
  S15P11A705-119(Feed 정책·계약) · S15P11A705-120(Spring 구현)
- **주도(Driver)**: AI 파트

## 맥락

Feed 명세는 Place category·region을 후보 채널과 점수 입력으로 사용했지만, `core.place`에는 두 값을
저장하지 않는다. Record 생성 요청에도 category가 없고, region은 주소 문자열에서만 파생할 수 있다.
현재 FastAPI도 `placeMeta`를 받지만 Context 임베딩 입력은 본문 `text`뿐이다.

## 선택지

| 안 | 장점 | 단점 |
|---|---|---|
| **(a) MVP Feed에서 둘 다 제외** | DB·Front 계약과 백필이 필요 없고 현재 데이터 모델과 일치한다 | AI 미완료 Collection의 개인화 신호가 줄어든다 |
| (b) Place에 category·region 저장 | 기존 Feed 공식과 정확 필터 후보 채널을 유지할 수 있다 | DTO·DB·Front·백필·갱신 정책이 함께 필요하고 Kakao category는 비거나 거칠 수 있다 |
| (c) 주소 문자열을 후보 SQL에서 파싱 | 스키마 변경이 없다 | 인덱스를 사용할 수 없고 파싱 규칙이 쿼리에 퍼진다 |

## 결정

- MVP 후보 채널은 **최신·팔로우·무작위** 세 개로 둔다.
- 점수는 **팔로우·공개 Keyword·최신성·노출 패널티**로 계산한다.
- Profile과 Collection 특징 Cache에서 category·region을 제거한다.
- Feed Spring 구현은 **이정헌**이 `Team-PinLog/back`에서 담당한다. FastAPI에 Feed API나
  점수 계산을 추가하지 않는다.
- AI 파트 **김가현**은 Feed Spring 구현 범위에서 빠지고 별도 추가 AI 기능을 담당한다.
- 외부 API는 공용 `static/08_API_명세.md`를 정본으로 삼는다. 경로는
  `/api/core/v1/feed/collections`, 기본 크기는 20, cursor는 opaque이며 `requestId`는 별도 필드다.

## 향후 확장

category·region을 `placeMeta`로 Context 본문과 함께 임베딩할 수 있다. 입력 구성이 바뀌므로
`embedding_profile`을 새 버전으로 올리고 기존 데이터를 재임베딩하거나 구·신 Profile 병행 후
전환해야 한다. 의미 유사도에만 쓰면 DB 인덱스가 필요 없지만 정확한 지역 필터·후보 채널을 도입하면
정규화된 `region_code` 컬럼과 인덱스를 별도로 추가한다.

## 감수하는 것

- Keyword가 없는 사용자는 최신·팔로우·무작위 중심의 Cold Start 결과를 받는다.
- **Collection 쪽 Cold Start** — AI가 미완료인 Collection은 노출이 지연될 수 있다. region을 제외하기 전에는 이 항이 AI 완료 여부와 무관하게 계산돼 미완료 Collection도 점수를 얻었지만, 그 경로가 사라졌다. 이제 `keywordAffinity = 0`이므로 팔로우 관계가 없는 신규 발행분은 `recency`(w=0.125) 하나로 경쟁한다. 후보 채널에는 `keywords: []`로 포함되지만 **후보 포함과 노출은 다른 층위**이고, 최신 채널 배분을 60에서 100으로 올린 것은 후보 진입 기회를 넓힐 뿐 점수 열세를 해소하지 않는다. 실질 노출 경로는 팔로우와 최신 두 갈래로 좁아진다.
- 기존 4채널·category/region 가중치 실험은 후속 고도화로 미룬다.
- 나중에 Place metadata를 임베딩하면 Profile 전환과 재처리 비용이 발생한다.

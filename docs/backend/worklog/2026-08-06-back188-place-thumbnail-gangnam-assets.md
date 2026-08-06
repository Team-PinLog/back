# 시연용 표지 이미지를 실제 강남 장소 사진으로 채운다

- **날짜**: 2026-08-06
- **추적**: [back#188](https://github.com/Team-PinLog/back/issues/188) (Jira 티켓 없음 — 코드 변경이 아니라 자산 수급이다)
- **관련**: [2026-08-04 목업 도입](2026-08-04-S15P11A705-305-place-thumbnail-mock.md) · [2026-08-05 WebP 전환](2026-08-05-S15P11A705-320-place-thumbnail-webp.md) · [front#94](https://github.com/Team-PinLog/front/issues/94)

## 무엇을 넣었나

`src/main/resources/static/images/places/test/`에 실제 강남 장소 사진 6장을 WebP로 넣었다.
전부 4:3·1200×900이고, 출처표시만으로 쓸 수 있는 라이선스다.

| 파일 | 장소 | 라이선스 | 용량 | 원본 |
|---|---|---|---|---|
| `starfield-library.webp` | 별마당 도서관(코엑스) | CC BY 4.0 | 389KB | [Starfield Library COEX 20240218.jpg](https://commons.wikimedia.org/wiki/File:Starfield_Library_COEX_20240218.jpg) |
| `gontran-cherrier.webp` | 공트란 쉐리에 강남구 매장 | CC0 | 206KB | [Gontran Cherrier store in gangnam-gu…](https://commons.wikimedia.org/wiki/File:Gontran_Cherrier_store_in_gangnam-gu_P20250928_181911188_17B21FF7-B9CE-4E11-AC5A-B5A2EB1F0D5A.JPG) |
| `yeoksam-lounge.webp` | 역삼동 라운지 실내 | CC BY 3.0 | 124KB | [Yeoksam-dong, Gangnam-gu — panoramio (2)](https://commons.wikimedia.org/wiki/File:Yeoksam-dong,_Gangnam-gu,_Seoul,_South_Korea_-_panoramio_(2).jpg) |
| `dosan-park.webp` | 도산공원 | CC0 | 330KB | [Dosan Memorial Park — DSC00427.JPG](https://commons.wikimedia.org/wiki/File:Dosan_Memorial_Park_-_Seoul,_South_Korea_-_DSC00427.JPG) |
| `seolleung.webp` | 선릉과 정릉 | CC0 | 395KB | [Seolleung and Jeongneung Royal Tombs 2.jpg](https://commons.wikimedia.org/wiki/File:Seolleung_and_Jeongneung_Royal_Tombs_2.jpg) |
| `gangnam-daero.webp` | 강남대로 | CC BY 2.0 | 190KB | [강남대로-2009-4494111695…](https://commons.wikimedia.org/wiki/File:%EA%B0%95%EB%82%A8%EB%8C%80%EB%A1%9C-2009-4494111695_ee300ede58_c.jpg) |

CC BY 세 장은 출처표시 의무가 있다. 위 표가 그 표기이며, 화면 안에 크레딧을 둘 자리는 프론트가
썸네일을 붙일 때 함께 정한다.

파일명은 업종(`cafe-1` 같은)이 아니라 장소 이름으로 지었다. 실제 장소 사진이므로 어느 place에
붙일지가 이름에서 드러나야 한다. [PlaceThumbnailAssetTests](../../../src/test/java/com/pinlog/pinlogback/domain/place/PlaceThumbnailAssetTests.java)는
`src/test/resources`의 더미만 조회하므로 배포 리소스 이름은 테스트와 무관하다.

경로는 `test/` 하위를 유지했다. 사진 자체는 진짜지만 **어느 place에 붙일지가 아직 정해지지
않았고**, `SecurityConfig`의 `PUBLIC_STATIC_ASSETS`(`/images/places/**`)가 하위를 덮어
시큐리티 설정을 건드릴 필요가 없다. place 매칭이 끝나면 `images/places/` 바로 아래로 옮긴다.

## 장당 상한을 100KB에서 400KB로 올린다

**100KB는 실사진에 적용할 수 없는 값이었다.** 더미가 단색에 가까워서 통했던 숫자이고,
[8월 5일 항목](2026-08-05-S15P11A705-320-place-thumbnail-webp.md)에서 "실제 사진은 이만큼
줄지 않는다"고 이미 적어뒀지만 상한은 그대로 뒀다. 실사진으로 재보니 못 지킨다.

1200×900 · WebP q82 실측이다.

| 사진 | 1200×900 | 900×675 | 800×600 |
|---|---|---|---|
| 역삼동 라운지 | 124KB | 86KB | 74KB |
| 강남대로 | 190KB | 117KB | 95KB |
| 도산공원 | 330KB | 195KB | 157KB |
| 별마당 도서관 | 429KB | 243KB | 194KB |

100KB를 지키려면 가로를 800px 아래로 내리거나 품질을 q46까지 떨어뜨려야 한다. 후자는 눈에
띄게 뭉갠다. 반면 6장 전부 1200×900 q82로 넣어도 합계 1.6MB이고, jar에 들어가는 정적 파일
여섯 개일 뿐이다. **비율 4:3과 가로 1200px은 그대로 두고 용량 상한만 400KB로 올린다.**
별마당 도서관만 q78로 낮춰 400KB에 맞췄고 나머지는 q82다.

프론트 계약은 영향받지 않는다. front#94에 약속한 것은 4:3과 가로 1200px뿐이고, 확장자와
용량은 계약에 없다.

## 왜 다른 출처를 버렸나

**TourAPI(한국관광공사)는 네 가지가 겹쳐 못 썼다.** 서비스키를 받아 강남역 반경 3km의
관광지·문화시설·음식점 67곳을 실제로 조회해서 확인한 결과다.

1. **음식점 30곳은 사진이 전부 `cpyrhtDivCd=Type3`(공공누리 제3유형)다.** 제3유형은 변경
   금지라 4:3 크롭·리사이즈를 할 수 없다. 카페·식당은 여기서 전멸한다.
2. **제1유형 사진은 `originimgurl`이 이미 축소본이다.** 19곳 100여 장을 전부 내려받아
   확인했는데 가로 1200px을 넘는 것은 한 장뿐이었다. 대부분 700×467 또는 940×705다.
3. **모든 사진에 한국관광공사 워터마크가 박혀 있다.** 크롭으로 지우는 것은 출처 표시를
   제거하는 행위라 제1유형이라도 할 수 없고, 하단을 크게 잘라내면 구도가 남지 않는다.
4. 남은 19곳도 도서관·갤러리 내부 위주라 장소 표지로 약하다.

**무료 스톡(Openverse CC0)은 라이선스는 깨끗하지만 강남이 아니었다.** 라이선스 의무가 없는
대신 덴마크어 간판, 애틀랜타 스카이라인처럼 명백히 외국 사진이 나와 시연에서 티가 난다.

**Wikimedia Commons는 CC BY-SA 비중이 높다.** 봉은사·선정릉 사진 다수가 여기 해당한다.
동일조건변경허락이 붙어 표시 화면에 크레딧을 두는 원칙이 따라오므로, 출처표시만 요구하는
CC0 · CC BY · 공공누리 제1유형으로 좁혀서 골랐다.

## 자동 수집만으로 고르면 안 된다

'Gangnam Station' 검색 결과에 **강남역 살인사건 추모 현장 사진 2장**이 라이선스 조건을
만족한 채로 섞여 있었다. 라이선스·해상도·비율만 기계로 걸렀으면 후보에 그대로 남았을 것이다.
같은 이유로 사람 얼굴이 식별되는 사진과 상표가 크게 잡힌 사진도 눈으로 걸렀다.

파일명이 `경기도 과천시 양재천`·`Gwacheon`인 사진도 'Yangjaecheon' 검색에 걸려 들어왔다.
양재천이 과천과 강남을 모두 지나므로 검색어만 믿으면 강남이 아닌 사진을 넣게 된다.

## 남은 일

back#188의 2단계다. **배포 DB에서 시연에 쓸 place를 고르고 `thumbnail_url`을 연결해야 한다.**
사진이 실제 장소 사진이므로 이제는 아무 place에나 붙일 수 없다 — 별마당 도서관 사진은 별마당
도서관 place에만 붙는다. 매칭되는 place가 없으면 그 사진은 쓰지 않는다.

```sql
UPDATE core.place SET thumbnail_url = '/api/core/images/places/test/starfield-library.webp'
WHERE id = <매칭한 place id>;
```

배포 DB 쓰기 권한은 아직 없다. infra#189는 담당자가 `minyongP`으로 잡혀 인프라에 전달되지
않았으므로, 같은 권한을 요청하는 [infra#116](https://github.com/Team-PinLog/infra/issues/116)으로
합치는 것이 실제 경로다.

## 덧 — TourAPI 경로로 11곳을 더 넣었다

위에서 "TourAPI는 못 쓴다"고 적었는데, **규격을 낮추면 쓸 수 있는 범위가 있어** 별도 묶음으로
추가했다. 앞의 6장(Commons)과 목적이 다르다 — 저쪽은 잘 나온 표지 사진이고, 이쪽은 **실제 강남
place에 정확히 연결되는 사진**이다.

경로는 `static/images/places/tourapi/`로 나눴다. 규격과 라이선스 근거가 다르니 섞지 않는다.

**규격이 다르다: 4:3 · 800×600 · WebP · 19~130KB.** TourAPI 제1유형 원본이 940×626~940×705라
가로 1200px을 만들 수 없다. 없는 화소를 만들지 않으려고 업스케일 대신 800×600으로 내렸다
(700×467 원본인 향기억·10꼬르소꼬모 두 곳만 소폭 확대됐다). **모든 사진에 한국관광공사 워터마크가
우하단에 남는다** — 지우면 출처 표시 제거라 그대로 뒀다.

파일명은 `<kakao_place_id>.webp`다. SQL의 조인 키와 같아서 어긋날 자리가 없다.

### 어떻게 골랐나

강남구 TourAPI 167곳 → 제1유형 사진 보유 19곳 → 카카오 매칭 13곳 → 검증 통과 11곳.

카카오는 TourAPI `addr1`을 주소 검색해 좌표를 얻고, 그 반경 1km에서 이름으로 찾았다. 매칭 뒤
두 가지를 더 걸렀다.

- **카카오 결과 주소에 '강남구'가 없으면 버린다.** TourAPI '갤러리에스피'가 카카오에서는 용산구
  이태원동 동명 갤러리로 잡혔다.
- **부분 일치인데 TourAPI 원제목이 4자 미만이면 버린다.** 원제목 '강남'이 '강남메디컬투어센터'에
  붙었다. 그 사진이 그 센터 사진일 리 없다.

### 검증

로컬 Postgres(`back-postgres-1`, 실제 V3 스키마)에 시드 SQL을 `COMMIT`을 `ROLLBACK`으로 바꿔
실행했다. `INSERT 0 11`과 확인 쿼리 11행이 나온 뒤 롤백했다. 로컬에 이미 있던 코엑스·플랫폼엘이
`ON CONFLICT` 갱신 경로를 태워 양쪽 분기가 모두 돌았다.

SQL은 레포에 두지 않는다. 한 번 실행하고 끝나는 운영 작업이라 back#188 댓글에 붙였다.

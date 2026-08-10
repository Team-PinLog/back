# place 썸네일 더미를 WebP로 교체하고 실제 이미지 경로를 확정한다

- **날짜**: 2026-08-05
- **추적**: Jira 작업
- **관련**: [2026-08-04 목업 도입](2026-08-04-place-thumbnail-mock.md) · [front#94](https://github.com/Team-PinLog/front/issues/94) · Jira 작업

## WebP로 되돌렸다

썸네일은 처음부터 WebP로 계획했으나 로컬에 인코더가 없어 JPEG로 생성했다(Jira 작업).
이번에 Pillow로 인코딩이 되는 것을 확인해 계획대로 되돌렸다. 더미 4장을 1200×900(4:3) 그대로
변환했고 26~32KB에서 7~11KB로 줄었다 — 다만 더미가 단색에 가까워 나온 값이라 실제 사진은
이만큼 줄지 않는다. 장당 100KB 상한은 그대로 둔다.

`cwebp`와 ImageMagick은 이 PC에 없다. `convert`가 PATH에 잡히지만 그것은 Windows의 FAT
변환기(`System32\convert.exe`)이므로 이미지 변환에 쓰면 안 된다.

**MIME 매핑은 손댈 필요가 없었다.** `image/webp`로 서빙되지 않으면 매핑을 추가할 생각이었는데,
정적 서빙 테스트가 그대로 통과했다. 대신 Content-Type 단정을 테스트에 남겨 뒀다 — 매핑이
빠지면 `application/octet-stream`으로 나가고, 그 회귀를 잡는 자리가 없으면 조용히 넘어간다.

## 실제 이미지는 `images/places/test/`에 둔다

8월 4일 항목에 "표지가 나오면 `src/main/resources/static/images/places/`에 커밋한다"고 적었는데,
**`static/images/places/test/` 하위로 바꿨다.** 그 항목은 기록이라 고치지 않고 여기에 적는다.

`test/`를 낀 이유는 두 가지다. 시연용 목업이라는 것이 경로에서 드러나고, 나중에 실제 장소 사진을
받으면 `images/places/` 바로 아래로 분리하면 된다. 그리고 `SecurityConfig`의
`PUBLIC_STATIC_ASSETS`(`/images/places/**`)가 하위 경로까지 덮으므로 **시큐리티 설정과 정적
서빙 테스트를 고칠 필요가 없다.**

따라서 시연 준비 UPDATE의 값도 `/api/core/images/places/test/<파일명>`이 된다. back PR #184
본문에 적어 둔 SQL 원문은 `test/`가 없으므로 그대로 쓰면 안 된다.

## 포맷은 프론트 계약이 아니다

전달 이슈(front#94)에 "가로 1200px WebP"와 예시 경로 `cafe-1.webp`를 적어 놨는데, 구현이
JPEG로 끝나 어긋났다. 유승주가 착수 전에 이 불일치를 지적했다.

정정하면서 포맷 표기를 JPEG로 바꾸는 대신 **아예 뺐다.** 프론트는 받은 `thumbnailUrl`을
`<img src>`에 그대로 넣을 뿐이고 확장자는 백엔드가 리소스에 있는 파일로 결정한다. 예시 경로도
그것을 보고 확장자를 조립할 여지가 있어 지웠다. 계약으로 남긴 것은 4:3 비율과 가로 1200px뿐이다.

명세 문서(`docs` 레포 `08_API_명세.md`·`06_데이터모델_및_무결성.md`)는 애초에 포맷을 적지 않았고
경로도 `/api/core/images/places/…`로 열려 있어 고칠 것이 없었다. 어긋난 것은 전달 이슈뿐이었다.

## 남은 일

실제 표지 이미지 수급과 배포 DB 연결은 Jira 작업이다. 이미지 출처는 아직 정하지 않았고,
배포 DB 쓰기 권한은 infra#189로 요청 중이다. 그때까지 배포 환경의 `thumbnail_url`은 전부
`null`이라 프론트는 폴백 경로만 검증할 수 있다.

**더미를 main 리소스로 옮기지 않았다.** 배포 산출물에 실리면 UPDATE가 더미를 가리켜 시연에
가짜 사진이 뜰 수 있다.

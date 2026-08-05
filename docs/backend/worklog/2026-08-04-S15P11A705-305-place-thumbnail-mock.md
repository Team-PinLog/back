# record 상세 응답에 place 썸네일을 추가한다 (시연용 목업)

- **날짜**: 2026-08-04
- **추적**: S15P11A705-305
- **관련**: [back#182](https://github.com/Team-PinLog/back/issues/182) · [docs#47](https://github.com/Team-PinLog/docs/pull/47)

카카오 로컬 API는 장소 사진을 주지 않아 place에는 이미지가 없었다. 시연에서 상세 화면이
비어 보이는 문제를 실사진 수집 없이 풀기 위해, `core.place.thumbnail_url`(V7, nullable)을
추가하고 `/images/places/` 정적 경로의 이미지를 시연 대상 place에 SQL로 수동 연결하는
목업 단계를 택했다. 출처 후보로 카카오 이미지 검색 API(`/v2/search/image`)를 확인해
두었고, 전환 시 이번 컬럼·응답 계약(`PlaceSummaryResponse.thumbnailUrl`)을 그대로
재사용한다.

판단 두 가지:

- **저장값은 context-path 포함 절대 경로**(`/api/core/images/places/…`)다. 프론트가
  `<img src>`에 그대로 쓰고, 외부 URL로 전환해도 프론트는 바뀌지 않는다.
- **이미지 경로는 인증 제외**다. 정적 서빙 테스트가 404가 아니라 401로 실패하면서
  `anyRequest().authenticated()`에 막히는 걸 잡았다. `<img>` 요청에는 인증 컨텍스트가
  보장되지 않으므로 `PUBLIC_STATIC_ASSETS`(`/images/places/**`)를 permitAll로 열었다.

이미지는 WebP로 계획했으나 로컬에 인코더가 없어 JPEG(1200×900, 4:3, 장당 ≤31KB)로
생성했다. 계약(경로·비율·용량 상한)은 동일하다. 처음엔 이 더미를 main 정적 리소스에
뒀는데, 시연용 실제 이미지(표지)가 나오면 버려질 파일이라 **테스트 리소스로 옮겼다** —
테스트 클래스패스의 `static/`도 MockMvc에서 동일하게 서빙되므로 경로·인증 제외 검증은
유지되고, 배포 산출물에는 더미가 실리지 않는다. 표지가 나오면 같은 파일명 규약으로
`src/main/resources/static/images/places/`에 커밋한다. 시연 준비 SQL은 PR 본문에 기록한다.

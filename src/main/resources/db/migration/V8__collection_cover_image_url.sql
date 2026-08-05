-- Collection 표지 이미지 URL(S15P11A705-322). 파일은 이미지 서비스가 영속 보관·서빙하고
-- core는 최종본의 같은 origin 상대 경로(/image/files/*.webp)만 참조한다(API 명세 7.7).
-- 생성 경로는 채우지 않는다 — 표지 확정 후 PATCH(7.4)로 채우며 NULL은 표지 없음(정상)이다.
-- 패턴 검증은 요청 DTO가 담당한다(place.thumbnail_url과 같은 접근). 길이 300도 같은 규약이다.
ALTER TABLE core.collection ADD COLUMN cover_image_url varchar(300);

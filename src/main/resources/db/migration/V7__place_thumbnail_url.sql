-- 장소 대표 썸네일 URL(S15P11A705-305). 카카오 로컬 응답에는 사진이 없어 생성 경로가 채우지 않는다.
-- 시연용 목업 단계에서는 정적 리소스 경로를 SQL로 수동 연결하고, 이후 카카오 이미지 검색 API
-- 전환 시 같은 컬럼에 외부 절대 URL이 들어간다(데이터모델 2.3). 길이 300은 place_url 규약과 같다.
ALTER TABLE core.place ADD COLUMN thumbnail_url varchar(300);

-- 검증 전용 회원 1명을 만든다. id는 시퀀스가 준다 —
-- core.member.id가 generated always as identity라 예약 id는 OVERRIDING SYSTEM VALUE가 필요하고
-- 시퀀스와 어긋날 위험이 있다.
--
-- 출력: member_id 한 열 한 행. run.sh이 -t -A로 읽어 k6에 넘긴다.
INSERT INTO core.member DEFAULT VALUES
RETURNING id AS member_id;

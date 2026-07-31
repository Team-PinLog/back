-- 검증 전용 회원 1명을 만든다. id는 시퀀스가 준다 —
-- core.member.id가 generated always as identity라 예약 id는 OVERRIDING SYSTEM VALUE가 필요하고
-- 시퀀스와 어긋날 위험이 있다.
--
-- 출력: member_id 한 열 한 행. run.sh이 -t -A로 읽어 k6에 넘긴다.
-- QUIET가 없으면 -t -A로도 명령 태그("INSERT 0 1")가 두 번째 줄로 나와서
-- 받은 값이 숫자 검사에 걸린다. 호출자에게 head -1을 요구하지 않고 여기서 막는다.
\set QUIET on
INSERT INTO core.member DEFAULT VALUES
RETURNING id AS member_id;

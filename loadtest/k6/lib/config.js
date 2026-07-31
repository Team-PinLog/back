// 검증 하네스 공용 설정. k6 init 컨텍스트에서만 open()을 쓸 수 있으므로 토큰 적재는 모듈 최상단이다.

const raw = JSON.parse(open('../../artifacts/tokens.json'));

export const BASE = __ENV.BASE_URL || raw.baseUrl;

/** memberId(문자열) → access token */
export const TOKENS = raw.tokens;

/** run.sh이 넘기는 전용 테스트 회원 id. 쓰기는 이 회원으로만 한다. */
export const TEST_MEMBER = __ENV.TEST_MEMBER_ID;

/**
 * 읽기로만 만지는 고정 데이터. 대량 시드가 골든 id를 절대 바꾸지 않는다는 보장에 기댄다.
 * member 1=레코드 11 · 2=데이터 0건 · 3=레코드 2 · 4=레코드 12
 */
export const GOLDEN = {
  ownerMember: '1',
  emptyMember: '2',
  otherMember: '3',
  ownedRecordId: 1,
  ownedCollectionId: 1,
  kakaoPlaceId: 'SEED-0008',
  missingKakaoPlaceId: 'NOPE-9999',
};

/** 무거운 사용자. 지도·목록 응답 크기를 재는 자리다(레코드 697 · 컬렉션 59). */
export const HEAVY_MEMBER = '2792';

/**
 * 태그별 p95 임계. 2026-07-31 실측 p50의 3~10배로 잡았다.
 * 1 VU라 여유가 크지만, 2단계 부하에서 조일 자리를 미리 표시해 두는 값이다.
 */
export const THRESHOLDS = {
  'http_req_duration{kind:detail}': ['p(95)<100'],
  'http_req_duration{kind:list}': ['p(95)<300'],
  'http_req_duration{kind:map}': ['p(95)<300'],
  'http_req_duration{kind:feed}': ['p(95)<500'],
  'http_req_duration{kind:search}': ['p(95)<1500'],
  'http_req_duration{kind:write}': ['p(95)<300'],
  // 계약 검사가 하나라도 깨지면 실패로 끝낸다.
  checks: ['rate==1.0'],
};

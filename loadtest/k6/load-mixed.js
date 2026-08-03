// 혼합 여정 1판 — "실제 트래픽이면 어느 정도인가"의 참고치.
//
// 가중치(피드 40 · 지도 20 · 상세 25 · 쓰기 15)는 서비스 출시 전이라 **근거 없는 추정**이다.
// 보고서에도 같은 문구를 박는다. 부류 귀속이 목적이 아니므로 average·stress만 돌린다.
import { session, okEnvelope } from './lib/http.js';
import { GOLDEN } from './lib/config.js';
import { stagesFor } from './lib/profiles.js';

const PROFILE = __ENV.PROFILE || '';
if (PROFILE !== 'average' && PROFILE !== 'stress') {
  throw new Error(`혼합 여정은 average·stress 전용이다: ${PROFILE}`);
}
const POOL = (__ENV.POOL_IDS || '').split(',').filter(Boolean);
if (POOL.length === 0) {
  throw new Error('POOL_IDS가 비어 있다 — 쓰기 15%가 회원 풀을 쓴다');
}

export const options = {
  scenarios: {
    mixed: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: stagesFor(PROFILE),
      gracefulRampDown: '10s',
    },
  },
  // 서브메트릭 생성 장치(절대 안 깨지는 상한) — load-read.js의 주석 참고.
  thresholds: {
    'http_req_duration{kind:feed}': ['p(99)<86400000'],
    'http_req_duration{kind:map}': ['p(99)<86400000'],
    'http_req_duration{kind:detail}': ['p(99)<86400000'],
    'http_req_duration{kind:write}': ['p(99)<86400000'],
  },
};

export default function () {
  const roll = Math.random() * 100;
  if (roll < 40) {
    session(GOLDEN.ownerMember).get('/v1/feed/collections', 'feed');
  } else if (roll < 60) {
    session(GOLDEN.ownerMember).get('/v1/records/map', 'map');
  } else if (roll < 85) {
    session(GOLDEN.ownerMember).get(`/v1/records/${GOLDEN.ownedRecordId}`, 'detail');
  } else {
    const memberId = POOL[(__VU - 1) % POOL.length];
    const me = session(memberId);
    const created = okEnvelope(me.post('/v1/records', {
      place: {
        kakaoPlaceId: `LT-MIX-${memberId}-${__ITER}`,
        name: `혼합장소 ${memberId}-${__ITER}`,
        address: '서울 강남구 테헤란로 1',
        roadAddress: '서울 강남구 테헤란로 1',
        phone: null,
        placeUrl: null,
        lat: 37.5,
        lng: 127.03,
      },
      contextBody: `혼합 맥락 ${memberId}-${__ITER}`,
    }, 'write'));
    if (created !== null) {
      me.del(`/v1/records/${created.recordId}/force`, 'write');
    }
  }
}

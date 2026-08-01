// 쓰기 계열 부하. 시나리오 둘을 태그로 가른다.
//
// throughput: VU마다 전용 회원 — 잠금 경합 없는 순수 쓰기 처리량.
// contention: VU 10 고정이 한 공유 Record의 Context를 몰아친다 — BD-11 부모 행 잠금
//   직렬화의 판정 지점(1단계 관측 2번). VU 수를 프로파일과 무관하게 고정해야
//   프로파일 간 비교가 성립한다. smoke에서는 꺼진다.
import { session, okEnvelope } from './lib/http.js';
import { stagesFor, isSmoke, maxVus } from './lib/profiles.js';

const PROFILE = __ENV.PROFILE || 'smoke';
const POOL = (__ENV.POOL_IDS || '').split(',').filter(Boolean);
const SHARED_MEMBER = __ENV.SHARED_MEMBER;
const SHARED_RECORD = __ENV.SHARED_RECORD;

if (POOL.length < maxVus(PROFILE)) {
  throw new Error(`회원 풀(${POOL.length})이 최대 VU(${maxVus(PROFILE)})보다 작다`);
}

const scenarios = {
  throughput: {
    executor: 'ramping-vus',
    exec: 'throughput',
    startVUs: 0,
    stages: stagesFor(PROFILE),
    gracefulRampDown: '10s',
  },
};

if (!isSmoke(PROFILE)) {
  scenarios.contention = {
    executor: 'constant-vus',
    exec: 'contention',
    vus: 10,
    // 처리량 시나리오와 같은 시간 동안 돈다.
    duration: stagesFor(PROFILE)
      .reduce((sec, s) => sec + parseDuration(s.duration), 0) + 's',
  };
}

export const options = {
  scenarios,
  // 서브메트릭 생성 장치(절대 안 깨지는 상한) — load-read.js의 주석 참고.
  thresholds: {
    'http_req_duration{kind:write}': ['p(99)<86400000'],
    'http_req_duration{kind:contention}': ['p(99)<86400000'],
  },
};

function parseDuration(d) {
  // '1m36s' 같은 k6 duration을 초로. 시나리오 duration 계산에만 쓴다.
  let sec = 0;
  const m = d.match(/(?:(\d+)m)?(?:(\d+)s)?/);
  if (m[1]) sec += Number(m[1]) * 60;
  if (m[2]) sec += Number(m[2]);
  return sec;
}

/** 처리량: 생성 → 맥락 추가 → 교체 → 컬렉션 생성·연결 → force 삭제. 회원당 place는 반복마다 유일. */
export function throughput() {
  const memberId = POOL[(__VU - 1) % POOL.length];
  const me = session(memberId);
  const suffix = `${memberId}-${__ITER}`;

  const created = me.post('/v1/records', {
    place: {
      kakaoPlaceId: `LT-${suffix}`,
      name: `부하장소 ${suffix}`,
      address: '서울 강남구 테헤란로 1',
      roadAddress: '서울 강남구 테헤란로 1',
      phone: null,
      placeUrl: null,
      lat: 37.5,
      lng: 127.03,
    },
    contextBody: `부하 맥락 ${suffix}`,
  }, 'write');
  const record = okEnvelope(created);
  if (record === null) return; // 실패 분포는 http_req_failed·상태 집계가 담는다

  const added = okEnvelope(
    me.post(`/v1/records/${record.recordId}/contexts`, { body: `추가 맥락 ${suffix}` }, 'write')
  );
  if (added !== null) {
    me.patch(`/v1/records/${record.recordId}/contexts/${added.contextId}`,
      { body: `교체 맥락 ${suffix}` }, 'write');
  }

  const col = okEnvelope(
    me.post('/v1/collections', { title: '부하컬렉션', recordIds: [record.recordId] }, 'write')
  );

  // 삭제 순서가 중요하다. Collection을 먼저 지우고(204) 그 다음 Record를 force로 지운다(204).
  // 반대로 하면 record force가 Collection을 연쇄로 소프트 삭제해서, 뒤이은 Collection 삭제가
  // 매번 404가 된다 — 실제 오류가 아닌데 오류율을 1/7만큼 부풀려 보고서 수치를 오염시킨다.
  // 이 순서면 두 삭제 엔드포인트를 다 부하로 치면서 상태 코드가 깨끗하다.
  if (col !== null) {
    me.del(`/v1/collections/${col.collectionId}`, 'write');
  }
  me.del(`/v1/records/${record.recordId}/force`, 'write');
}

/** 경합: 공유 Record에 Context 추가→삭제 반복. BD-11 잠금이 여기서 직렬화된다. */
export function contention() {
  const me = session(SHARED_MEMBER);
  const added = okEnvelope(
    me.post(`/v1/records/${SHARED_RECORD}/contexts`,
      { body: `경합 맥락 ${__VU}-${__ITER}` }, 'contention')
  );
  if (added !== null) {
    // 시드로 넣어 둔 기준 Context 2개 덕에 마지막-삭제 409에 걸리지 않는다.
    me.del(`/v1/records/${SHARED_RECORD}/contexts/${added.contextId}`, 'contention');
  }
}

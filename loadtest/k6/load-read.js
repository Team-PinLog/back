// 읽기 계열 부하. VU를 부류(지도·목록·상세·피드)에 고정하고 kind 태그로 지연을 가른다.
//
// 부류별 별도 실행 대신 동시 타격을 택한 이유: 실제 DB는 어차피 혼합 부하를 받고,
// 태그 귀속이면 부류별 지연이 갈라진다. 별도 실행은 시간이 4배다.
//
// 읽기 전용이라 회원 풀이 필요 없다 — 골든·heavy 토큰만 쓴다.
// 1단계 THRESHOLDS를 걸지 않는다: 관측이 목적이라 임계 실패로 실행이 끊기면 안 된다.
import { session } from './lib/http.js';
import { GOLDEN, HEAVY_MEMBER } from './lib/config.js';
import { stagesFor } from './lib/profiles.js';

const PROFILE = __ENV.PROFILE || 'smoke';

export const options = {
  scenarios: {
    read: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: stagesFor(PROFILE),
      gracefulRampDown: '10s',
    },
  },
  // 게이트가 아니라 서브메트릭 생성 장치다. k6는 임계값이 참조하는 태그 조합만
  // summary-export에 별도 항목으로 실어 준다 — 이 절대 안 깨지는 상한(24시간)이 없으면
  // kind별 지연이 요약에 안 남아 collate-load.py가 빈 표를 만든다.
  thresholds: {
    'http_req_duration{kind:map}': ['p(99)<86400000'],
    'http_req_duration{kind:list}': ['p(99)<86400000'],
    'http_req_duration{kind:detail}': ['p(99)<86400000'],
    'http_req_duration{kind:feed}': ['p(99)<86400000'],
  },
};

// 부류 순서는 고정이다 — __VU % 4가 프로파일과 무관하게 같은 분배를 주도록.
const CLASSES = ['map', 'list', 'detail', 'feed'];

export default function () {
  const cls = CLASSES[__VU % CLASSES.length];
  // 지도 부류의 절반은 heavy 회원(마커 697개·61KB)으로 — 관측 1번(대역폭·직렬화) 판정용.
  const useHeavy = cls === 'map' && __VU % 2 === 0;
  const me = session(useHeavy ? HEAVY_MEMBER : GOLDEN.ownerMember);

  if (cls === 'map') {
    me.get('/v1/records/map', 'map');
  } else if (cls === 'list') {
    me.get('/v1/collections', 'list');
  } else if (cls === 'detail') {
    me.get(`/v1/records/${GOLDEN.ownedRecordId}`, 'detail');
  } else {
    me.get('/v1/feed/collections', 'feed');
  }
}

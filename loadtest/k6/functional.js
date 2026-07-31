// 27개 엔드포인트 전수 시나리오. 1 VU 1 iteration으로 순서대로 돈다.
//
// 시나리오가 순서 의존적이라(만든 것을 이어서 만지고 지운다) 한 파일에 둔다.
// 도메인별 함수로 나눠 각 함수를 짧게 유지한다.
import { GOLDEN, HEAVY_MEMBER, TEST_MEMBER, THRESHOLDS } from './lib/config.js';
import { session, expect, okEnvelope, errorOf } from './lib/http.js';
import { touch, visit, emit } from './lib/recorder.js';

export const options = { vus: 1, iterations: 1, thresholds: THRESHOLDS };

/** 전수 판정 기준. 27개 전부 여기 있어야 한다. */
export const ENDPOINTS = [
  'GET /v1/auth/{provider}/login',
  'POST /v1/auth/refresh',
  'POST /v1/auth/logout',
  'POST /v1/records',
  'GET /v1/records/map',
  'GET /v1/records/by-place',
  'GET /v1/records/{id}',
  'POST /v1/records/{id}/contexts',
  'PATCH /v1/records/{id}/contexts/{cid}',
  'DELETE /v1/records/{id}/contexts/{cid}',
  'DELETE /v1/records/{id}',
  'DELETE /v1/records/{id}/force',
  'POST /v1/collections',
  'GET /v1/collections',
  'GET /v1/collections/{id}',
  'PATCH /v1/collections/{id}',
  'POST /v1/collections/{id}/records',
  'DELETE /v1/collections/{id}/records/{rid}',
  'DELETE /v1/collections/{id}',
  'POST /v1/follows',
  'GET /v1/follows',
  'GET /v1/follows/{followId}/collections',
  'PATCH /v1/follows/{followId}',
  'DELETE /v1/follows/{followId}',
  'GET /v1/feed/collections',
  'POST /v1/feed/events',
  'POST /v1/search/records',
];

/** 커서 목록 모양을 본다(BD-04). */
function checkCursorPage(data, label) {
  const ok =
    data !== null &&
    Array.isArray(data.items) &&
    typeof data.hasNext === 'boolean' &&
    'nextCursor' in data &&
    data.hasNext === (data.nextCursor !== null);
  if (!ok) {
    console.error(`[FAIL] ${label} CursorPage 계약 위반: ${JSON.stringify(data).slice(0, 300)}`);
  }
  return ok;
}

export function runReads(ctx) {
  // --- 지도 ---
  const mapAll = ctx.owner.get('/v1/records/map', 'map');
  expect(mapAll, 'GET /v1/records/map (전체)', 200);
  visit('GET /v1/records/map');
  const markers = okEnvelope(mapAll);
  if (markers === null || !Array.isArray(markers.items) || !('bounds' in markers)) {
    console.error('[FAIL] map 응답에 items·bounds가 없다');
  }

  const mapBox = ctx.owner.get(
    '/v1/records/map?swLat=37.4&swLng=126.8&neLat=37.7&neLng=127.1',
    'map'
  );
  expect(mapBox, 'GET /v1/records/map (서울 bbox)', 200);

  // 무거운 사용자의 지도 응답 크기를 기록한다. 마커 697개에 페이지네이션이 없다.
  const mapHeavy = ctx.heavy.get('/v1/records/map', 'map');
  expect(mapHeavy, 'GET /v1/records/map (heavy)', 200);
  const heavyMarkers = okEnvelope(mapHeavy);
  console.log(
    `[측정] heavy 지도 마커 ${heavyMarkers === null ? '?' : heavyMarkers.items.length}개 / ` +
      `본문 ${mapHeavy.body.length}바이트`
  );

  // --- 장소로 조회 ---
  const byPlace = ctx.owner.get(
    `/v1/records/by-place?kakaoPlaceId=${GOLDEN.kakaoPlaceId}`,
    'detail'
  );
  expect(byPlace, 'GET /v1/records/by-place (있음)', 200);
  visit('GET /v1/records/by-place');
  const found = okEnvelope(byPlace);
  if (found === null || found.record === null) {
    console.error('[FAIL] 골든 place에 record가 없다');
  }

  const byPlaceMissing = ctx.owner.get(
    `/v1/records/by-place?kakaoPlaceId=${GOLDEN.missingKakaoPlaceId}`,
    'detail'
  );
  expect(byPlaceMissing, 'GET /v1/records/by-place (없음)', 200);
  const notFound = okEnvelope(byPlaceMissing);
  if (notFound === null || notFound.record !== null) {
    console.error('[FAIL] 없는 place인데 record가 null이 아니다');
  }

  // kakaoPlaceId 누락은 400이다.
  expect(
    ctx.owner.get('/v1/records/by-place', 'detail'),
    'GET /v1/records/by-place (파라미터 누락)',
    { status: 400, code: 'INVALID_INPUT' }
  );

  // --- Record 상세 ---
  const recordDetail = ctx.owner.get(`/v1/records/${GOLDEN.ownedRecordId}`, 'detail');
  expect(recordDetail, `GET /v1/records/${GOLDEN.ownedRecordId}`, 200);
  visit('GET /v1/records/{id}');
  const record = okEnvelope(recordDetail);
  if (record === null || !record.place || !Array.isArray(record.contexts)) {
    console.error('[FAIL] record 상세에 place·contexts가 없다');
  } else {
    // BD-25: contexts는 createdAt 오름차순, 동률이면 id 오름차순이다.
    // createdAt은 origin_created_at을 담는다.
    for (let i = 1; i < record.contexts.length; i += 1) {
      const prev = record.contexts[i - 1];
      const cur = record.contexts[i];
      const outOfOrder =
        prev.createdAt > cur.createdAt ||
        (prev.createdAt === cur.createdAt && prev.contextId > cur.contextId);
      if (outOfOrder) {
        console.error(
          `[FAIL] contexts 정렬 위반(BD-25): ${prev.contextId}@${prev.createdAt} > ` +
            `${cur.contextId}@${cur.createdAt}`
        );
        break;
      }
    }
  }

  // --- 목록 ---
  const collections = ctx.owner.get('/v1/collections', 'list');
  expect(collections, 'GET /v1/collections', 200);
  visit('GET /v1/collections');
  checkCursorPage(okEnvelope(collections), 'GET /v1/collections');

  // size 상한 초과는 400이 아니라 100으로 보정된다(S15P11A705-117 규약).
  const oversize = ctx.heavy.get('/v1/collections?size=1000', 'list');
  expect(oversize, 'GET /v1/collections?size=1000', 200);
  const capped = okEnvelope(oversize);
  if (capped !== null && capped.items.length > 100) {
    console.error(`[FAIL] size 상한 보정 실패: ${capped.items.length}건`);
  }

  const collectionDetail = ctx.owner.get(`/v1/collections/${GOLDEN.ownedCollectionId}`, 'detail');
  expect(collectionDetail, `GET /v1/collections/${GOLDEN.ownedCollectionId}`, 200);
  visit('GET /v1/collections/{id}');
  const owned = okEnvelope(collectionDetail);
  if (owned === null || owned.ownedByMe !== true) {
    console.error('[FAIL] 소유자 조회인데 ownedByMe가 true가 아니다');
  }

  // BD-13: 타인 공개 조회는 다른 DTO다. 소유자용 필드가 없어야 한다.
  const publicView = ctx.other.get(`/v1/collections/${GOLDEN.ownedCollectionId}`, 'detail');
  expect(publicView, 'GET /v1/collections/{id} (타인 공개)', 200);
  const publicData = okEnvelope(publicView);
  if (publicData === null || publicData.ownedByMe !== false) {
    console.error('[FAIL] 타인 조회인데 ownedByMe가 false가 아니다');
  }

  const follows = ctx.owner.get('/v1/follows', 'list');
  expect(follows, 'GET /v1/follows', 200);
  visit('GET /v1/follows');
  const followPage = okEnvelope(follows);
  checkCursorPage(followPage, 'GET /v1/follows');

  if (followPage !== null && followPage.items.length > 0) {
    const followId = followPage.items[0].followId;
    const followed = ctx.owner.get(`/v1/follows/${followId}/collections`, 'list');
    expect(followed, `GET /v1/follows/${followId}/collections`, 200);
    visit('GET /v1/follows/{followId}/collections');
    checkCursorPage(okEnvelope(followed), 'GET /v1/follows/{followId}/collections');
  } else {
    console.error('[FAIL] 골든 회원에 follow가 없어 팔로우 목록을 못 돌았다');
  }

  // --- 빈 결과 ---
  const emptyMap = ctx.empty.get('/v1/records/map', 'map');
  expect(emptyMap, 'GET /v1/records/map (데이터 0건 회원)', 200);
  const emptyData = okEnvelope(emptyMap);
  if (emptyData === null || emptyData.items.length !== 0) {
    console.error('[FAIL] 데이터 0건 회원인데 마커가 있다');
  }

  // --- 인증·소유권 부정 계약 ---
  expect(ctx.owner.anon('GET', '/v1/collections', 'list'), '쿠키 없음', {
    status: 401,
    code: 'UNAUTHORIZED',
  });

  expect(
    ctx.empty.get(`/v1/records/${GOLDEN.ownedRecordId}`, 'detail'),
    '남의 record (은닉 404)',
    { status: 404, code: 'RESOURCE_NOT_FOUND' }
  );

  // CSRF 헤더 없이 POST하면 403이다. anon은 CSRF 헤더를 붙이지 않는다.
  expect(ctx.owner.anon('POST', '/v1/collections', 'write'), 'CSRF 없는 POST', {
    status: 403,
    code: 'FORBIDDEN',
  });
}

export default function () {
  const ctx = {
    owner: session(GOLDEN.ownerMember),
    other: session(GOLDEN.otherMember),
    empty: session(GOLDEN.emptyMember),
    heavy: session(HEAVY_MEMBER),
    test: session(TEST_MEMBER),
  };

  runReads(ctx);

  emit(ENDPOINTS);
}

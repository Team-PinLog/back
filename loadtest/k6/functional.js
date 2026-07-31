// 29개 엔드포인트 전수 시나리오. 1 VU 1 iteration으로 순서대로 돈다.
//
// 시나리오가 순서 의존적이라(만든 것을 이어서 만지고 지운다) 한 파일에 둔다.
// 도메인별 함수로 나눠 각 함수를 짧게 유지한다.
import { GOLDEN, HEAVY_MEMBER, TEST_MEMBER, THRESHOLDS } from './lib/config.js';
import { session, expect, okEnvelope, errorOf } from './lib/http.js';
import { touch, visit, emit } from './lib/recorder.js';

export const options = { vus: 1, iterations: 1, thresholds: THRESHOLDS };

/** 전수 판정 기준. 29개 전부 여기 있어야 한다. */
export const ENDPOINTS = [
  'GET /v1/auth/{provider}/login',
  'POST /v1/auth/refresh',
  'POST /v1/auth/logout',
  'GET /v1/me/summary',
  'DELETE /v1/me',
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
  if (capped === null) {
    console.error('[FAIL] size=1000 응답이 성공 엔벨로프가 아니다');
  } else if (capped.items.length > 100) {
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

/** 전용 회원으로만 쓴다. 좌표는 서울 안, kakaoPlaceId는 시드와 겹치지 않는 접두어를 쓴다. */
function testPlace(suffix) {
  return {
    kakaoPlaceId: `LT-${TEST_MEMBER}-${suffix}`,
    name: `검증장소 ${suffix}`,
    address: '서울 강남구 테헤란로 1',
    roadAddress: '서울 강남구 테헤란로 1',
    phone: null,
    placeUrl: null,
    lat: 37.5,
    lng: 127.03,
  };
}

export function runRecordWrites(ctx) {
  const me = ctx.test;

  // --- 생성: 201 RECORD_CREATED ---
  const first = me.post(
    '/v1/records',
    { place: testPlace('a'), contextBody: '검증용 첫 맥락이다.' },
    'write'
  );
  expect(first, 'POST /v1/records (신규)', 201);
  visit('POST /v1/records');
  const firstData = okEnvelope(first);
  if (firstData === null || firstData.result !== 'RECORD_CREATED') {
    console.error(`[FAIL] 신규 생성인데 result가 RECORD_CREATED가 아니다: ${first.body}`);
    return { recordId: null, contextId: null };
  }
  const recordId = firstData.recordId;
  touch('record', recordId, 'POST /v1/records로 생성');

  // --- BD-12: 같은 place 재요청은 새 Record가 아니라 Context 추가이고 200이다 ---
  const again = me.post(
    '/v1/records',
    { place: testPlace('a'), contextBody: '같은 장소 두 번째 맥락이다.' },
    'write'
  );
  expect(again, 'POST /v1/records (같은 place 재요청)', 200);
  const againData = okEnvelope(again);
  if (againData === null || againData.result !== 'CONTEXT_ADDED') {
    console.error(`[FAIL] BD-12 위반: 같은 place 재요청 result=${JSON.stringify(againData)}`);
  } else if (againData.recordId !== recordId) {
    console.error(
      `[FAIL] BD-12 위반: 같은 place인데 recordId가 다르다 ${againData.recordId} != ${recordId}`
    );
  }

  // --- 좌표 범위 밖은 400 ---
  const badPlace = testPlace('bad');
  badPlace.lat = 91;
  expect(
    me.post('/v1/records', { place: badPlace, contextBody: '범위 밖 좌표' }, 'write'),
    'POST /v1/records (lat=91)',
    { status: 400, code: 'INVALID_INPUT' }
  );

  // --- Context 추가: 201 ---
  const added = me.post(
    `/v1/records/${recordId}/contexts`,
    { body: '세 번째 맥락이다.' },
    'write'
  );
  expect(added, 'POST /v1/records/{id}/contexts', 201);
  visit('POST /v1/records/{id}/contexts');
  const addedData = okEnvelope(added);
  const contextId = addedData === null ? null : addedData.contextId;
  if (contextId === null) {
    console.error(`[FAIL] Context 추가 응답에 contextId가 없다: ${added.body}`);
    return { recordId: recordId, contextId: null };
  }
  touch('context', contextId, 'POST contexts로 추가');

  // 빈 본문과 상한 초과는 400이다(InputLimits.CONTEXT_BODY_MAX = 500).
  expect(
    me.post(`/v1/records/${recordId}/contexts`, { body: '' }, 'write'),
    'POST contexts (빈 본문)',
    { status: 400, code: 'INVALID_INPUT' }
  );
  expect(
    me.post(`/v1/records/${recordId}/contexts`, { body: 'ㄱ'.repeat(501) }, 'write'),
    'POST contexts (501자)',
    { status: 400, code: 'INVALID_INPUT' }
  );

  // --- BD-07: 수정은 교체다. 새 contextId가 나와야 한다 ---
  const replaced = me.patch(
    `/v1/records/${recordId}/contexts/${contextId}`,
    { body: '교체된 맥락이다.' },
    'write'
  );
  expect(replaced, 'PATCH /v1/records/{id}/contexts/{cid}', 200);
  visit('PATCH /v1/records/{id}/contexts/{cid}');
  const replacedData = okEnvelope(replaced);
  if (replacedData === null) {
    console.error(`[FAIL] 교체 응답 엔벨로프가 아니다: ${replaced.body}`);
  } else if (replacedData.contextId === contextId) {
    console.error(`[FAIL] BD-07 위반: 교체인데 contextId가 그대로다 ${contextId}`);
  } else {
    touch('context', replacedData.contextId, 'PATCH로 교체 생성');
    touch('context', contextId, 'PATCH로 소프트 삭제됨');
  }
  const liveContextId = replacedData === null ? contextId : replacedData.contextId;

  // 남의 Context 교체는 404다.
  expect(
    ctx.owner.patch(
      `/v1/records/${recordId}/contexts/${liveContextId}`,
      { body: '남이 고치려 함' },
      'write'
    ),
    'PATCH contexts (남의 것)',
    { status: 404, code: 'RESOURCE_NOT_FOUND' }
  );

  // --- Context 삭제: 남는 것이 있으면 204 ---
  const del = me.del(`/v1/records/${recordId}/contexts/${liveContextId}`, 'write');
  expect(del, 'DELETE /v1/records/{id}/contexts/{cid}', 204);
  visit('DELETE /v1/records/{id}/contexts/{cid}');
  touch('context', liveContextId, 'DELETE로 소프트 삭제');

  return { recordId: recordId, contextId: liveContextId };
}

export function runCollectionWrites(ctx, created) {
  const me = ctx.test;
  if (created.recordId === null) {
    console.error('[FAIL] recordId가 없어 Collection 시나리오를 건너뛴다');
    return { collectionId: null };
  }

  // --- 생성: 201 ---
  const create = me.post(
    '/v1/collections',
    { title: '검증컬렉션', recordIds: [created.recordId] },
    'write'
  );
  expect(create, 'POST /v1/collections', 201);
  visit('POST /v1/collections');
  const createData = okEnvelope(create);
  const collectionId = createData === null ? null : createData.collectionId;
  if (collectionId === null) {
    console.error(`[FAIL] Collection 생성 응답에 collectionId가 없다: ${create.body}`);
    return { collectionId: null };
  }
  touch('collection', collectionId, 'POST /v1/collections로 생성');
  touch('collection_record', collectionId, `record ${created.recordId} 연결됨`);

  // BD-33: is_published가 참이면 published_at이 반드시 있다. 응답으로도 확인한다.
  if (createData.publishedAt === null || createData.publishedAt === undefined) {
    console.error(`[FAIL] BD-33 의심: 생성 직후 publishedAt이 비어 있다: ${create.body}`);
  }
  if (createData.recordCount !== 1) {
    console.error(`[FAIL] 생성 직후 recordCount가 1이 아니다: ${createData.recordCount}`);
  }

  // 빈 recordIds와 title 상한 초과는 400이다(CollectionCreateRequest: @NotEmpty, @Size(max=20)).
  expect(
    me.post('/v1/collections', { title: '빈배열', recordIds: [] }, 'write'),
    'POST /v1/collections (빈 recordIds)',
    { status: 400, code: 'INVALID_INPUT' }
  );
  expect(
    me.post('/v1/collections', { title: 'ㄱ'.repeat(21), recordIds: [created.recordId] }, 'write'),
    'POST /v1/collections (title 21자)',
    { status: 400, code: 'INVALID_INPUT' }
  );

  // --- 이름 바꾸기 ---
  const rename = me.patch(`/v1/collections/${collectionId}`, { title: '이름바꿈' }, 'write');
  expect(rename, 'PATCH /v1/collections/{id}', 200);
  visit('PATCH /v1/collections/{id}');
  const renamed = okEnvelope(rename);
  if (renamed === null || renamed.title !== '이름바꿈') {
    console.error(`[FAIL] 이름이 바뀌지 않았다: ${rename.body}`);
  }
  expect(
    me.patch(`/v1/collections/${collectionId}`, { title: 'ㄱ'.repeat(21) }, 'write'),
    'PATCH /v1/collections/{id} (21자)',
    { status: 400, code: 'INVALID_INPUT' }
  );

  // --- Record 추가와 멱등성(API 명세 7.5) ---
  const second = me.post(
    '/v1/records',
    { place: testPlace('b'), contextBody: '두 번째 장소의 맥락이다.' },
    'write'
  );
  expect(second, 'POST /v1/records (두 번째 장소)', 201);
  const secondData = okEnvelope(second);
  const secondRecordId = secondData === null ? null : secondData.recordId;
  if (secondRecordId !== null) {
    touch('record', secondRecordId, '두 번째 검증 Record');
  }

  const add = me.post(
    `/v1/collections/${collectionId}/records`,
    { recordIds: [secondRecordId] },
    'write'
  );
  expect(add, 'POST /v1/collections/{id}/records', 200);
  visit('POST /v1/collections/{id}/records');
  const added = okEnvelope(add);
  if (added === null || added.recordCount !== 2) {
    console.error(`[FAIL] 추가 후 recordCount가 2가 아니다: ${add.body}`);
  }
  touch('collection_record', collectionId, `record ${secondRecordId} 연결됨`);

  // 같은 id를 다시 넣어도 200이고 recordCount는 그대로다.
  const addAgain = me.post(
    `/v1/collections/${collectionId}/records`,
    { recordIds: [secondRecordId] },
    'write'
  );
  expect(addAgain, 'POST /v1/collections/{id}/records (멱등)', 200);
  const addedAgain = okEnvelope(addAgain);
  if (addedAgain !== null && addedAgain.recordCount !== 2) {
    console.error(`[FAIL] 멱등 위반: 재추가 후 recordCount=${addedAgain.recordCount}`);
  }

  // 상한 초과는 400이다(InputLimits.RECORD_IDS_MAX = 100).
  const tooMany = [];
  for (let i = 0; i < 101; i += 1) tooMany.push(created.recordId);
  expect(
    me.post(`/v1/collections/${collectionId}/records`, { recordIds: tooMany }, 'write'),
    'POST /v1/collections/{id}/records (101개)',
    { status: 400, code: 'INVALID_INPUT' }
  );

  // --- 연결 해제: 남는 것이 있으면 204 ---
  const remove = me.del(`/v1/collections/${collectionId}/records/${secondRecordId}`, 'write');
  expect(remove, 'DELETE /v1/collections/{id}/records/{rid}', 204);
  visit('DELETE /v1/collections/{id}/records/{rid}');

  // --- BD-11: 마지막 연결 해제는 409 + impact ---
  const removeLast = me.del(
    `/v1/collections/${collectionId}/records/${created.recordId}`,
    'write'
  );
  expect(removeLast, 'DELETE 마지막 연결 (409 기대)', {
    status: 409,
    code: 'DELETE_CONFIRMATION_REQUIRED',
  });
  const lastErr = errorOf(removeLast);
  if (lastErr === null || lastErr.impact === undefined) {
    console.error(`[FAIL] BD-11 위반: 409에 error.impact가 없다: ${removeLast.body}`);
  } else if (typeof lastErr.impact.recordDeleted !== 'boolean' ||
             !Array.isArray(lastErr.impact.collectionIds)) {
    console.error(`[FAIL] impact 모양 위반: ${JSON.stringify(lastErr.impact)}`);
  }

  // --- BD-11: 그 Collection의 마지막 Record 삭제도 409 + impact.collectionIds ---
  const deleteRecord = me.del(`/v1/records/${created.recordId}`, 'write');
  expect(deleteRecord, 'DELETE /v1/records/{id} (마지막이라 409 기대)', {
    status: 409,
    code: 'DELETE_CONFIRMATION_REQUIRED',
  });
  visit('DELETE /v1/records/{id}');
  const recErr = errorOf(deleteRecord);
  if (recErr === null || recErr.impact === undefined) {
    console.error(`[FAIL] BD-11 위반: record 삭제 409에 impact가 없다: ${deleteRecord.body}`);
  } else if (recErr.impact.collectionIds.indexOf(collectionId) === -1) {
    console.error(
      `[FAIL] impact.collectionIds에 ${collectionId}가 없다: ` +
        `${JSON.stringify(recErr.impact.collectionIds)}`
    );
  }

  // --- force로 확정 삭제 ---
  const force = me.del(`/v1/records/${created.recordId}/force`, 'write');
  expect(force, 'DELETE /v1/records/{id}/force', 204);
  visit('DELETE /v1/records/{id}/force');
  touch('record', created.recordId, 'force로 연쇄 삭제');
  touch('collection', collectionId, 'force 연쇄로 함께 삭제되었을 수 있음');

  // 남의 것 force는 404다.
  expect(ctx.owner.del(`/v1/records/${created.recordId}/force`, 'write'), 'force (남의 것)', {
    status: 404,
    code: 'RESOURCE_NOT_FOUND',
  });

  // --- Collection 삭제: 별도 Collection을 하나 더 만들어 확인한다 ---
  const spare = me.post(
    '/v1/records',
    { place: testPlace('c'), contextBody: '세 번째 장소의 맥락이다.' },
    'write'
  );
  expect(spare, 'POST /v1/records (삭제 확인용)', 201);
  const spareData = okEnvelope(spare);
  const spareRecordId = spareData === null ? null : spareData.recordId;
  if (spareRecordId !== null) {
    touch('record', spareRecordId, '삭제 확인용 Record');
  }

  const spareCollection = me.post(
    '/v1/collections',
    { title: '삭제될컬렉션', recordIds: [spareRecordId] },
    'write'
  );
  const spareCollectionData = okEnvelope(spareCollection);
  const spareCollectionId =
    spareCollectionData === null ? null : spareCollectionData.collectionId;

  if (spareCollectionId !== null) {
    touch('collection', spareCollectionId, '삭제 확인용 Collection');
    const deleteCollection = me.del(`/v1/collections/${spareCollectionId}`, 'write');
    expect(deleteCollection, 'DELETE /v1/collections/{id}', 204);
    visit('DELETE /v1/collections/{id}');

    expect(
      ctx.owner.del(`/v1/collections/${spareCollectionId}`, 'write'),
      'DELETE /v1/collections/{id} (남의 것)',
      { status: 404, code: 'RESOURCE_NOT_FOUND' }
    );
  } else {
    console.error(`[FAIL] 삭제 확인용 Collection을 못 만들었다: ${spareCollection.body}`);
  }

  return { collectionId: spareCollectionId };
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
  const created = runRecordWrites(ctx);
  if (created.recordId === null) {
    console.error('[FAIL] Record 쓰기 시나리오가 recordId를 만들지 못했다');
  }
  const collection = runCollectionWrites(ctx, created);
  if (collection.collectionId === null) {
    console.error('[FAIL] Collection 쓰기 시나리오가 collectionId를 만들지 못했다');
  }
  runFollowWrites(ctx);
  runFeedAndSearch(ctx);
  runAuth(ctx);
  runWithdrawal(ctx);

  emit(ENDPOINTS);
}

export function runFollowWrites(ctx) {
  const me = ctx.test;

  // 골든 회원 1의 공개 Collection을 팔로우한다.
  const follow = me.post('/v1/follows', { collectionId: GOLDEN.ownedCollectionId }, 'write');
  expect(follow, 'POST /v1/follows', 201);
  visit('POST /v1/follows');
  const followData = okEnvelope(follow);
  const followId = followData === null ? null : followData.followId;
  if (followId === null) {
    console.error(`[FAIL] follow 응답에 followId가 없다: ${follow.body}`);
    return;
  }
  touch('follow', followId, 'POST /v1/follows로 생성');

  // 같은 것을 다시 팔로우하면 409다.
  expect(
    me.post('/v1/follows', { collectionId: GOLDEN.ownedCollectionId }, 'write'),
    'POST /v1/follows (중복)',
    { status: 409, code: 'DUPLICATE_FOLLOW' }
  );

  // 자기 Collection 팔로우는 422다(ck_follow_self가 DB에서도 막는다).
  // Collection 1의 주인이 골든 회원 1이므로 그 회원으로 보내면 자기 팔로우가 된다.
  expect(
    ctx.owner.post('/v1/follows', { collectionId: GOLDEN.ownedCollectionId }, 'write'),
    'POST /v1/follows (자기 것)',
    { status: 422, code: 'SELF_FOLLOW_NOT_ALLOWED' }
  );

  // 팔로우한 책장의 Collection 목록.
  const followed = me.get(`/v1/follows/${followId}/collections`, 'list');
  expect(followed, 'GET /v1/follows/{followId}/collections', 200);
  visit('GET /v1/follows/{followId}/collections');
  checkCursorPage(okEnvelope(followed), 'GET /v1/follows/{followId}/collections');

  // 남의 follow는 404다.
  expect(
    ctx.owner.get(`/v1/follows/${followId}/collections`, 'list'),
    'GET follows/{id}/collections (남의 것)',
    { status: 404, code: 'RESOURCE_NOT_FOUND' }
  );

  // 별칭 설정 → 제거. null은 제거를 뜻한다(API 명세 8.3).
  const alias = me.patch(`/v1/follows/${followId}`, { alias: '내별칭' }, 'write');
  expect(alias, 'PATCH /v1/follows/{followId} (설정)', 200);
  visit('PATCH /v1/follows/{followId}');
  const aliasData = okEnvelope(alias);
  if (aliasData === null || aliasData.alias !== '내별칭') {
    console.error(`[FAIL] 별칭이 설정되지 않았다: ${alias.body}`);
  }

  const cleared = me.patch(`/v1/follows/${followId}`, { alias: null }, 'write');
  expect(cleared, 'PATCH /v1/follows/{followId} (제거)', 200);
  const clearedData = okEnvelope(cleared);
  if (clearedData !== null && clearedData.alias !== null && clearedData.alias !== undefined) {
    console.error(`[FAIL] 별칭이 제거되지 않았다: ${cleared.body}`);
  }

  expect(
    me.patch(`/v1/follows/${followId}`, { alias: 'ㄱ'.repeat(21) }, 'write'),
    'PATCH /v1/follows/{followId} (21자)',
    { status: 400, code: 'INVALID_INPUT' }
  );

  // 언팔로우.
  expect(me.del(`/v1/follows/${followId}`, 'write'), 'DELETE /v1/follows/{followId}', 204);
  visit('DELETE /v1/follows/{followId}');
  expect(
    ctx.owner.del(`/v1/follows/${followId}`, 'write'),
    'DELETE /v1/follows/{followId} (남의 것)',
    { status: 404, code: 'RESOURCE_NOT_FOUND' }
  );
}

export function runFeedAndSearch(ctx) {
  const me = ctx.owner;

  // --- Feed 후보 ---
  const feed = me.get('/v1/feed/collections', 'feed');
  expect(feed, 'GET /v1/feed/collections', 200);
  visit('GET /v1/feed/collections');
  const feedData = okEnvelope(feed);
  if (feedData === null || !Array.isArray(feedData.items)) {
    console.error(`[FAIL] feed 응답에 items가 없다: ${feed.body}`);
    return;
  }
  if (typeof feedData.requestId !== 'string') {
    console.error(`[FAIL] feed 응답에 requestId(UUID)가 없다: ${feed.body}`);
  }

  // --- Feed 이벤트 수집 ---
  // core.feed_event는 AI 파트 소유(V102)다. 상태 코드 계약만 보고 DB 단정은 하지 않는다.
  if (feedData.items.length > 0 && typeof feedData.requestId === 'string') {
    const target = feedData.items[0];
    const collect = me.post(
      '/v1/feed/events',
      {
        requestId: feedData.requestId,
        events: [{ event: 'CLICK', collectionId: target.collectionId, position: 0 }],
      },
      'write'
    );
    expect(collect, 'POST /v1/feed/events', 204);
    visit('POST /v1/feed/events');

    // IMPRESSION은 서버가 기록한다. 클라이언트가 보내면 거부된다(FeedEventType.isClientReportable).
    expect(
      me.post(
        '/v1/feed/events',
        {
          requestId: feedData.requestId,
          events: [{ event: 'IMPRESSION', collectionId: target.collectionId, position: 0 }],
        },
        'write'
      ),
      'POST /v1/feed/events (IMPRESSION 거부)',
      { status: 400, code: 'INVALID_INPUT' }
    );

    // 배열 상한 초과는 잘라내지 않고 400으로 거절한다(InputLimits.FEED_EVENTS_MAX = 100).
    const many = [];
    for (let i = 0; i < 101; i += 1) {
      many.push({ event: 'CLICK', collectionId: target.collectionId, position: i });
    }
    expect(
      me.post('/v1/feed/events', { requestId: feedData.requestId, events: many }, 'write'),
      'POST /v1/feed/events (101개)',
      { status: 400, code: 'INVALID_INPUT' }
    );
  } else {
    console.error('[FAIL] feed 후보가 0건이라 이벤트 수집을 못 돌았다');
  }

  // --- 자연어 검색 ---
  // FastAPI(8000)가 없으면 503 SEARCH_UNAVAILABLE이 계약이다. 빈 결과로 치환하지 않는다.
  const search = ctx.heavy.post(
    '/v1/search/records',
    { query: '조용한 카페', size: 5 },
    'search'
  );
  visit('POST /v1/search/records');
  if (search.status === 200) {
    expect(search, 'POST /v1/search/records', 200);
    const searchData = okEnvelope(search);
    if (searchData === null || !Array.isArray(searchData.items)) {
      console.error(`[FAIL] 검색 응답에 items가 없다: ${search.body}`);
    } else {
      console.log(`[측정] 검색 결과 ${searchData.items.length}건`);
    }
  } else {
    expect(search, 'POST /v1/search/records (AI 서버 없음)', {
      status: 503,
      code: 'SEARCH_UNAVAILABLE',
    });
  }

  expect(
    ctx.heavy.post('/v1/search/records', { query: '' }, 'search'),
    'POST /v1/search/records (빈 질의)',
    { status: 400, code: 'INVALID_INPUT' }
  );
  expect(
    ctx.heavy.post('/v1/search/records', { query: 'ㄱ'.repeat(501) }, 'search'),
    'POST /v1/search/records (501자)',
    { status: 400, code: 'INVALID_INPUT' }
  );
}

export function runAuth(ctx) {
  const me = ctx.test;

  // --- 로그인 진입: 302 ---
  // /v1/auth/**는 permitAll이다. 리다이렉트를 따라가면 302를 못 보므로 redirects: 0을 준다.
  const login = me.get('/v1/auth/google/login', 'detail', { redirects: 0 });
  visit('GET /v1/auth/{provider}/login');
  const locationOk = String(login.headers['Location'] || '').indexOf(
    '/api/core/v1/auth/authorize/google'
  ) === 0;
  if (login.status !== 302 || !locationOk) {
    console.error(
      `[FAIL] 로그인 진입 계약 위반 status=${login.status} Location=${login.headers['Location']}`
    );
  }

  // 없는 provider는 404다(UnsupportedSocialProviderException → RESOURCE_NOT_FOUND).
  expect(
    me.get('/v1/auth/facebook/login', 'detail', { redirects: 0 }),
    'GET /v1/auth/facebook/login',
    { status: 404, code: 'RESOURCE_NOT_FOUND' }
  );

  // --- 재발급: refresh 쿠키가 없으면 401 ---
  // 정상 경로는 실제 소셜 로그인 왕복이 필요해 여기서 돌 수 없다. README에 적는다.
  const refresh = me.post('/v1/auth/refresh', null, 'write');
  visit('POST /v1/auth/refresh');
  expect(refresh, 'POST /v1/auth/refresh (쿠키 없음)', {
    status: 401,
    code: 'UNAUTHORIZED',
  });

  // CSRF 헤더 없는 재발급은 403이다.
  expect(ctx.test.anon('POST', '/v1/auth/refresh', 'write'), 'POST refresh (CSRF 없음)', {
    status: 403,
    code: 'FORBIDDEN',
  });

  // --- 로그아웃: 멱등하게 204 ---
  const logout = me.post('/v1/auth/logout', null, 'write');
  visit('POST /v1/auth/logout');
  expect(logout, 'POST /v1/auth/logout', 204);

  // BD-21: 토큰은 쿠키로만 오간다. 응답 본문에 실리지 않는다.
  // 로그아웃은 AuthCookies.clear()로 두 쿠키를 지우므로 여기서 쿠키 속성을 관찰할 수 있다.
  // 발급 시점(소셜 로그인 콜백)은 실제 OAuth 왕복이 필요해 이 하네스로 못 본다.
  if (String(logout.body || '').length !== 0) {
    console.error(`[FAIL] BD-21 위반 의심: logout 응답에 본문이 있다: ${logout.body}`);
  }
  const clearedCookies = logout.cookies || {};
  ['access_token', 'refresh_token'].forEach((name) => {
    const entries = clearedCookies[name];
    if (!entries || entries.length === 0) {
      console.error(`[FAIL] logout이 ${name} 쿠키를 지우지 않았다`);
      return;
    }
    if (entries[0].http_only !== true) {
      console.error(`[FAIL] ${name}이 HttpOnly가 아니다`);
    }
    if (entries[0].value !== '') {
      console.error(`[FAIL] logout인데 ${name} 값이 비어 있지 않다: ${entries[0].value}`);
    }
  });

  expect(ctx.test.anon('POST', '/v1/auth/logout', 'write'), 'POST logout (CSRF 없음)', {
    status: 403,
    code: 'FORBIDDEN',
  });
}

/**
 * 회원 탈퇴(API 명세 3.6). 반드시 모든 시나리오의 마지막이어야 한다 — 이 뒤로 전용 회원의
 * 토큰은 전부 401이다.
 *
 * 탈퇴 파급의 실증을 겸한다: 전역 불변식 스윕이 teardown 전에 돌므로,
 * MemberWithdrawalService가 record·context·collection·연결·follow 중 하나라도 빠뜨리면
 * "BI-12 탈퇴 회원의 살아있는 Record" 같은 검사가 이 회원을 잡아 back_violations가 올라간다.
 */
export function runWithdrawal(ctx) {
  const me = ctx.test;

  // 마이페이지 요약(API 명세 3.5). memberId를 담지 않는 것이 계약이다(BD-14 식별자 은닉).
  const summary = me.get('/v1/me/summary', 'detail');
  expect(summary, 'GET /v1/me/summary', 200);
  visit('GET /v1/me/summary');
  const summaryData = okEnvelope(summary);
  if (summaryData === null) {
    console.error(`[FAIL] me/summary 응답이 성공 엔벨로프가 아니다: ${summary.body}`);
  } else if ('memberId' in summaryData) {
    console.error(`[FAIL] BD-14 위반: me/summary가 memberId를 노출한다: ${summary.body}`);
  } else if (typeof summaryData.recordCount !== 'number') {
    console.error(`[FAIL] me/summary에 recordCount가 없다: ${summary.body}`);
  }

  // CSRF 없는 탈퇴는 403이다.
  expect(ctx.test.anon('DELETE', '/v1/me', 'write'), 'DELETE /v1/me (CSRF 없음)', {
    status: 403,
    code: 'FORBIDDEN',
  });

  // 본문 없는 204. 쿠키 정리는 응답이 담당하지만 우리 토큰은 발급형이라 관찰 대상이 아니다.
  const withdraw = me.del('/v1/me', 'write');
  expect(withdraw, 'DELETE /v1/me', 204);
  visit('DELETE /v1/me');
  touch(
    'member',
    Number(TEST_MEMBER),
    'DELETE /v1/me로 탈퇴 (파급: record·context·collection·연결·follow 소프트 삭제)'
  );

  // BD-41: 탈퇴한 회원의 살아있는 Access 토큰은 인증에서 거절된다.
  expect(me.get('/v1/collections', 'list'), 'GET /v1/collections (탈퇴 후)', {
    status: 401,
    code: 'UNAUTHORIZED',
  });
}

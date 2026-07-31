// 공용 라이브러리 검증 전용. Task 4에서 functional.js로 대체하고 삭제한다.
import { GOLDEN, THRESHOLDS } from './lib/config.js';
import { session, expect, okEnvelope } from './lib/http.js';
import { touch, visit, emit } from './lib/recorder.js';

export const options = { vus: 1, iterations: 1, thresholds: THRESHOLDS };

const ALL = ['GET /v1/collections', 'GET /v1/records/{id}'];

export default function () {
  const me = session(GOLDEN.ownerMember);

  const list = me.get('/v1/collections', 'list');
  expect(list, 'GET /v1/collections', 200);
  visit('GET /v1/collections');
  const page = okEnvelope(list);
  if (page === null || !Array.isArray(page.items) || typeof page.hasNext !== 'boolean') {
    console.error('[FAIL] CursorPage 모양이 아니다');
  }

  const detail = me.get(`/v1/records/${GOLDEN.ownedRecordId}`, 'detail');
  expect(detail, `GET /v1/records/${GOLDEN.ownedRecordId}`, 200);
  visit('GET /v1/records/{id}');
  touch('record', GOLDEN.ownedRecordId, '읽기만 함');

  emit(ALL);
}

// k6는 실행 중 파일을 쓸 수 없고, handleSummary는 VU 런타임 상태를 보지 못한다.
// 그래서 표준출력에 표식 한 줄을 흘리고 run.sh이 그것을 잘라내 artifacts/touched.json으로 만든다.

const MARKER = '##TOUCHED##';

const touched = [];
const visited = {};

/**
 * 이번 실행이 만들거나 바꾼 행을 적는다.
 *
 * @param kind {string} 'record' | 'context' | 'collection' | 'collection_record' | 'follow' | 'member'
 * @param id {number}
 * @param note {string} 무엇을 했는지. 실패 진단에 쓴다
 */
export function touch(kind, id, note) {
  touched.push({ kind: kind, id: Number(id), note: note });
}

/** 호출한 엔드포인트를 표시한다. 전수 목록의 호출 여부를 run.sh이 판정한다. */
export function visit(endpointKey) {
  visited[endpointKey] = (visited[endpointKey] || 0) + 1;
}

/**
 * 표식 한 줄을 출력한다. 시나리오 마지막에 정확히 한 번 부른다.
 *
 * @param allEndpointKeys {string[]} 전수 목록의 키 전체. 호출되지 않은 것을 골라낸다
 */
export function emit(allEndpointKeys) {
  const missed = allEndpointKeys.filter((key) => !visited[key]);
  const payload = {
    testMember: __ENV.TEST_MEMBER_ID || null,
    touched: touched,
    visited: visited,
    missedEndpoints: missed,
  };
  console.log(`${MARKER} ${JSON.stringify(payload)}`);
  if (missed.length > 0) {
    console.error(`[FAIL] 호출되지 않은 엔드포인트 ${missed.length}개: ${missed.join(', ')}`);
  }
}

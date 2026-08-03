// 프로파일 4종의 stages 정의. 부하 스크립트 셋이 공유한다.
//
// 계단마다 90초 이상 머무는 이유: 포화는 과도기(램프 중)와 안정기(계단 위)에서 다르게
// 보이는데, 머무는 시간이 짧으면 둘이 섞여 "어느 VU에서 포화했나"를 특정할 수 없다.

export const PROFILES = ['smoke', 'average', 'stress', 'spike'];

const STAGES = {
  // 시나리오 자체 회귀 확인. 경합 시나리오는 이 프로파일에서 꺼진다.
  // 첫 단계 0s는 VU를 즉시 1로 올리는 장치다 — startVUs 0에서 "1분에 걸쳐 0→1"로 두면
  // 보간이 내내 0이라 요청이 한 건도 안 나가고, 빈 실행이 exit 0으로 통과해 버린다(실측).
  smoke: [
    { duration: '0s', target: 1 },
    { duration: '1m', target: 1 },
  ],
  // 평상 부하 기준선.
  average: [
    { duration: '30s', target: 20 },
    { duration: '4m30s', target: 20 },
  ],
  // 포화 지점 탐색. 계단당 96초.
  stress: [
    { duration: '1m36s', target: 20 },
    { duration: '1m36s', target: 40 },
    { duration: '1m36s', target: 60 },
    { duration: '1m36s', target: 80 },
    { duration: '1m36s', target: 100 },
  ],
  // 급증 회복력.
  spike: [
    { duration: '30s', target: 5 },
    { duration: '30s', target: 150 },
    { duration: '2m', target: 150 },
    { duration: '30s', target: 5 },
    { duration: '30s', target: 0 },
  ],
};

export function stagesFor(profile) {
  const stages = STAGES[profile];
  if (!stages) {
    throw new Error(`알 수 없는 프로파일: ${profile}. 가능한 값: ${PROFILES.join(', ')}`);
  }
  return stages;
}

export function isSmoke(profile) {
  return profile === 'smoke';
}

/** 프로파일의 최대 VU. 회원 풀 크기·preAllocatedVUs 산정에 쓴다. */
export function maxVus(profile) {
  return Math.max(...stagesFor(profile).map((s) => s.target));
}

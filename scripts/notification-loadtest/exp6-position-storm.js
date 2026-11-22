// 실험 6. 대기열이 바뀔 때마다 ACTIVE 전원에게 순번 알림이 나가서, 변경이 잦으면 전송량이 폭증한다.
//   N명 접속 중에 점주가 초당 CALL_RATE번씩 CALLS번 호출 → 총 전송 = CALLS × N.
//   호출된 웨이팅은 CALLED가 되어도 ACTIVE로 남아 누구의 순번도 바뀌지 않는다.
//   그래서 사용자 1명이 받는 CALLS건은 전부 같은 순번을 다시 받은 것이다(순번 비교·묶음 전송의 근거).
//   실행: k6 run -e N=200 -e CALLS=50 -e CALL_RATE=5 scripts/notification-loadtest/exp6-position-storm.js
import exec from 'k6/execution';
import { N, createWaitings, ownerCall, ownerCalls, subscribe, myMemberId, makeSummary } from './lib.js';

const CALLS = parseInt(__ENV.CALLS || '50', 10);
const CALL_RATE = parseInt(__ENV.CALL_RATE || '5', 10);
const OWNER_START = 5;
const OWNER_SECONDS = Math.ceil(CALLS / CALL_RATE);
const SUBSCRIBE_SECONDS = OWNER_START + OWNER_SECONDS + 5;

if (CALLS > N) throw new Error(`CALLS(${CALLS})는 N(${N}) 이하여야 한다`);

export const options = {
    scenarios: {
        subscribers: {
            executor: 'per-vu-iterations', vus: N, iterations: 1, exec: 'subscriber',
            maxDuration: `${SUBSCRIBE_SECONDS + 10}s`,
        },
        owner_storm: {
            executor: 'constant-arrival-rate', rate: CALL_RATE, timeUnit: '1s',
            duration: `${OWNER_SECONDS}s`, preAllocatedVUs: 5, maxVUs: 20,
            startTime: `${OWNER_START}s`, exec: 'owner',
        },
    },
};

export function setup() {
    return { waitings: createWaitings(N) };
}

export function subscriber() {
    subscribe(myMemberId(), `${SUBSCRIBE_SECONDS}s`);
}

export function owner(data) {
    const i = exec.scenario.iterationInTest;
    if (i >= CALLS) return;
    ownerCall(data.waitings[i].waitingId);
    ownerCalls.add(1);
}

export const handleSummary = makeSummary('exp6-position-storm', (r) => {
    const e = r.eventsPerConnection;
    const f = (v) => (v === undefined ? '-' : v.toFixed(1));
    return [
        `조건: 구독자 ${N}명, ${OWNER_SECONDS}초 동안 호출 ${r.ownerCalls}건 (초당 ${CALL_RATE})`,
        `서버가 보낸 순번 알림: ${r.received.positionChanged}건 (기대 ${r.ownerCalls} × ${N} = ${r.ownerCalls * N})`,
        `사용자 1명이 받은 알림: 평균 ${f(e.avg)}건 (순번 알림 ${r.ownerCalls}건, 호출된 ${r.ownerCalls}명은 호출 알림 1건 추가)`,
        `순번이 실제로 바뀐 알림: 0건 (호출은 대기열에서 아무도 빼지 않아 순번이 그대로다)`,
        `개선 기대치(계산값): 순번이 바뀐 사람에게만 보내면 0건, 매장당 1초 모아 보내면 약 ${OWNER_SECONDS} × ${N} = ${OWNER_SECONDS * N}건`,
    ];
});

// 실험 5. 점주의 /call 응답 시간이 접속 중인 대기 인원 수에 따라 늘어난다.
//   알림 전송이 요청 스레드에서 동기로 실행되기 때문(@Async 없음). 호출 1번에 ACTIVE 전원에게 순번 알림을 보낸다.
//   N을 바꿔 가며 같은 조건으로 CALLS번 호출하고 응답 시간 분포(p50·p95)를 비교한다.
//   로컬이라 절대값이 아니라 N에 따른 증가 추세만 근거로 쓴다.
//   실행 (각 N 사이에 reset.sql 실행):
//     k6 run -e N=10  scripts/notification-loadtest/exp5-call-latency.js
//     k6 run -e N=100 scripts/notification-loadtest/exp5-call-latency.js
//     k6 run -e N=500 scripts/notification-loadtest/exp5-call-latency.js
import exec from 'k6/execution';
import { sleep } from 'k6';
import { N, createWaitings, ownerCall, subscribe, myMemberId, makeSummary, connectedWindow, callThresholds } from './lib.js';

const CALLS = parseInt(__ENV.CALLS || String(Math.min(10, N)), 10);
const CALL_INTERVAL = 0.3; // 초. 호출 사이 간격 (이전 전송이 끝난 뒤 재기 위해)
const OWNER_START = 5;
const window = connectedWindow(OWNER_START, Math.ceil(CALLS * (CALL_INTERVAL + 2)));
const SUBSCRIBE_SECONDS = window.subscription;

if (CALLS > N) throw new Error(`CALLS(${CALLS})는 N(${N}) 이하여야 한다`);

export const options = {
    thresholds: callThresholds(CALLS),
    scenarios: {
        subscribers: {
            executor: 'per-vu-iterations', vus: N, iterations: 1, exec: 'subscriber',
            maxDuration: `${SUBSCRIBE_SECONDS + 10}s`,
        },
        owner_calls: {
            executor: 'shared-iterations', vus: 1, iterations: CALLS, exec: 'owner',
            startTime: `${OWNER_START}s`, maxDuration: `${window.owner}s`,
        },
    },
};

export function setup() {
    return { waitings: createWaitings(N) };
}

export function subscriber() {
    subscribe(myMemberId(), `${SUBSCRIBE_SECONDS}s`, undefined, true);
}

export function owner(data) {
    ownerCall(data.waitings[exec.scenario.iterationInTest].waitingId);
    sleep(CALL_INTERVAL);
}

export const handleSummary = makeSummary('exp5-call-latency', (r) => {
    const d = r.ownerCallDurationMs;
    const f = (v) => (v === undefined ? '-' : v.toFixed(1));
    return [
        `조건: 구독자 ${N}명 접속 중, 점주 호출 ${r.ownerCalls}회`,
        `/call 응답 시간(ms): 평균 ${f(d.avg)}, p50 ${f(d.med)}, p90 ${f(d['p(90)'])}, p95 ${f(d['p(95)'])}, 최대 ${f(d.max)}`,
        `호출마다 전송된 순번 알림: 총 ${r.received.positionChanged}건 (= 호출 수 × 구독자 수 ${r.ownerCalls * N})`,
    ];
}, { expectedCalls: CALLS, measureWindow: true });

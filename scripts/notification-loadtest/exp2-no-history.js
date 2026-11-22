// 실험 2. 접속 중에 실시간으로 받은 알림도, 끊었다 다시 접속하면 다시 볼 수 없다.
//   흐름: N명 SSE 접속(1차) → 점주가 전원 호출 → 1차 연결에서 수신 수 집계 → 연결 종료
//         → N명 재접속(2차) → 2차 연결에서 수신 수 집계
//   기대: 1차 WAITING_CALLED N건, 2차 0건
//   실행: k6 run -e N=100 scripts/notification-loadtest/exp2-no-history.js
import exec from 'k6/execution';
import { Counter } from 'k6/metrics';
import { N, createWaitings, ownerCall, subscribe, myMemberId, makeSummary, connectedWindow, callThresholds } from './lib.js';

const OWNER_START = 3;
const window = connectedWindow(OWNER_START, Math.max(10, Math.ceil(N * 0.5)));
const FIRST_SECONDS = window.subscription;
const SECOND_SECONDS = 5;
const RECONNECT_AT = FIRST_SECONDS + 3;

const phase1 = new Counter('phase1_received');
const phase2 = new Counter('phase2_received');

export const options = {
    thresholds: callThresholds(N),
    scenarios: {
        first_connect: {
            executor: 'per-vu-iterations', vus: N, iterations: 1, exec: 'firstConnect',
            maxDuration: `${FIRST_SECONDS + 10}s`,
        },
        owner_calls_all: {
            executor: 'shared-iterations', vus: 1, iterations: N, exec: 'owner',
            startTime: `${OWNER_START}s`, maxDuration: `${window.owner}s`,
        },
        reconnect: {
            executor: 'per-vu-iterations', vus: N, iterations: 1, exec: 'reconnect',
            startTime: `${RECONNECT_AT}s`, maxDuration: `${SECOND_SECONDS + 10}s`,
        },
    },
};

export function setup() {
    return { waitings: createWaitings(N) };
}

export function firstConnect() {
    subscribe(myMemberId(), `${FIRST_SECONDS}s`, () => phase1.add(1), true);
}

export function owner(data) {
    const w = data.waitings[exec.scenario.iterationInTest];
    ownerCall(w.waitingId);
}

export function reconnect() {
    subscribe(myMemberId(), `${SECOND_SECONDS}s`, () => phase2.add(1));
}

export const handleSummary = makeSummary('exp2-no-history', (r) => [
    `발생: 호출 ${r.ownerCalls}건 (전원 접속 중)`,
    `1차 접속 중 수신: ${r.extra.phase1_received}건 (WAITING_CALLED + 순번 변경)`,
    `재접속 후 수신: ${r.extra.phase2_received}건 → 이전 알림은 다시 볼 수 없음`,
], { expectedCalls: N, expectedConnections: 2 * N, measureWindow: true, extraCounters: ['phase1_received', 'phase2_received'] });

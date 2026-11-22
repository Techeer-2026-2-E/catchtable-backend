// 실험 3. 앱을 켜 두고 있어도, 연결이 끊겼다 다시 붙는 사이에 발생한 알림은 사라진다.
//   서버는 30분마다 SSE를 끊고(TIMEOUT_MILLIS) 브라우저 EventSource는 기본 3초 뒤 재연결한다.
//   이 실험은 같은 상황을 짧게 재현한다: 클라이언트가 RECONNECT_EVERY초마다 끊고 RECONNECT_DELAY초 뒤 다시 붙는다.
//   그 동안 점주는 1초에 CALL_RATE번 호출 → 호출마다 ACTIVE 전원에게 WAITING_POSITION_CHANGED 발생.
//   기대 수신 = 호출 수 × N. 실제 수신과의 차이가 유실.
//   실행: k6 run -e N=100 -e CALLS=50 scripts/notification-loadtest/exp3-reconnect-gap.js
import exec from 'k6/execution';
import { sleep } from 'k6';
import { N, createWaitings, ownerCall, ownerCalls, subscribe, myMemberId, makeSummary } from './lib.js';

const CALLS = parseInt(__ENV.CALLS || '50', 10);               // 총 호출 수 (N 이하)
const CALL_RATE = parseInt(__ENV.CALL_RATE || '1', 10);         // 초당 호출 수
const RECONNECT_EVERY = parseInt(__ENV.RECONNECT_EVERY || '5', 10); // 연결 유지 시간 (초)
const RECONNECT_DELAY = parseFloat(__ENV.RECONNECT_DELAY || '3');   // 재연결까지 대기 (초, EventSource 기본값 3초)
// 구독자마다 첫 접속 시각을 흩어 놓아(0 ~ 한 주기) 끊김이 한꺼번에 몰리지 않게 한다. 점주 호출은 그 뒤에 시작
const CYCLE = RECONNECT_EVERY + RECONNECT_DELAY;
const OWNER_START = Math.ceil(CYCLE) + 2;
const DURATION = OWNER_START + Math.ceil(CALLS / CALL_RATE) + 3;

if (CALLS > N) throw new Error(`CALLS(${CALLS})는 N(${N}) 이하여야 한다. 호출은 웨이팅 1건당 1번만 된다`);

export const options = {
    scenarios: {
        subscribers: {
            // VU 1개 = 고객 1명. 한 반복 안에서 DURATION 동안 끊고 붙기를 반복한다
            executor: 'per-vu-iterations', vus: N, iterations: 1, exec: 'subscriber',
            maxDuration: `${DURATION + RECONNECT_EVERY + 5}s`,
        },
        owner_calls: {
            executor: 'constant-arrival-rate', rate: CALL_RATE, timeUnit: '1s',
            duration: `${Math.ceil(CALLS / CALL_RATE)}s`, preAllocatedVUs: 2, maxVUs: 5,
            startTime: `${OWNER_START}s`, exec: 'owner',
        },
    },
};

export function setup() {
    return { waitings: createWaitings(N) };
}

export function subscriber() {
    const memberId = myMemberId();
    const end = Date.now() + DURATION * 1000;
    sleep(Math.random() * CYCLE);
    while (Date.now() < end) {
        subscribe(memberId, `${RECONNECT_EVERY}s`);
        sleep(RECONNECT_DELAY);
    }
}

export function owner(data) {
    const i = exec.scenario.iterationInTest;
    if (i >= CALLS) return;
    ownerCall(data.waitings[i].waitingId);
    ownerCalls.add(1);
}

export const handleSummary = makeSummary('exp3-reconnect-gap', (r) => {
    const expected = r.ownerCalls * N;
    const got = r.received.positionChanged;
    const lost = expected - got;
    return [
        `조건: 구독자 ${N}명, ${RECONNECT_EVERY}초 연결 후 ${RECONNECT_DELAY}초 끊김 반복, 호출 ${r.ownerCalls}건`,
        `기대 수신: ${r.ownerCalls} × ${N} = ${expected}건 (WAITING_POSITION_CHANGED)`,
        `실제 수신: ${got}건`,
        `유실: ${lost}건 (${expected ? ((lost / expected) * 100).toFixed(1) : 0}%, 끊김 비율 이론값 ${((RECONNECT_DELAY / CYCLE) * 100).toFixed(1)}%)`,
        `호출 당사자 알림(WAITING_CALLED): 발생 ${r.ownerCalls}건 / 수신 ${r.received.waitingCalled}건`,
    ];
});

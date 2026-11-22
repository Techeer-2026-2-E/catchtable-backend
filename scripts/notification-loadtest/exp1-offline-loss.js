// 실험 1. 접속하지 않은 동안 발생한 알림은 사라지고, 나중에 조회할 API도 없다.
//   흐름: setup에서 (아무도 접속 안 한 상태로) 점주가 N명 전원 호출 → 그 뒤 N명이 SSE 접속 → 받는 알림 수
//   기대: WAITING_CALLED 수신 0건, GET /api/user/notifications 404
//   실행: k6 run -e N=100 scripts/notification-loadtest/exp1-offline-loss.js
import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';
import { BASE_URL, N, createWaitings, ownerCall, subscribe, myMemberId, makeSummary, callThresholds } from './lib.js';

const SUBSCRIBE_SECONDS = parseInt(__ENV.SUBSCRIBE_SECONDS || '5', 10);
const listApi404 = new Counter('list_api_404');

export const options = {
    thresholds: callThresholds(N),
    scenarios: {
        late_subscribers: {
            executor: 'per-vu-iterations',
            vus: N,
            iterations: 1,
            maxDuration: `${SUBSCRIBE_SECONDS + 15}s`,
        },
    },
};

export function setup() {
    const waitings = createWaitings(N);
    for (const w of waitings) {
        ownerCall(w.waitingId);
    }
    console.log(`접속자 0명 상태에서 ${waitings.length}명 호출 완료 → 이제 고객이 접속한다`);
    return { waitings };
}

export default function () {
    const memberId = myMemberId();
    subscribe(memberId, `${SUBSCRIBE_SECONDS}s`);

    const res = http.get(`${BASE_URL}/api/user/notifications`,
        { headers: { 'X-Member-Id': String(memberId) }, tags: { name: 'list_notifications' } });
    if (check(res, { '알림 목록 API 없음 (404)': (r) => r.status === 404 })) listApi404.add(1);
}

export const handleSummary = makeSummary('exp1-offline-loss', (r) => [
    `발생: 접속자 0명일 때 호출 ${r.ownerCalls}건 (WAITING_CALLED ${r.ownerCalls}건 발생)`,
    `접속 후 ${r.params.SUBSCRIBE_SECONDS || 5}초 동안 수신: WAITING_CALLED ${r.received.waitingCalled}건`,
    `사후 조회: 알림 목록 API 없음 (GET /api/user/notifications → 404, ${r.extra.list_api_404}/${N}명 확인)`,
], { expectedCalls: N, expectedConnections: N, extraCounters: ['list_api_404'] });

// 실험 스크립트 공통 코드. k6 v2 (k6/x/sse 확장은 첫 실행 때 자동 설치).
import http from 'k6/http';
import exec from 'k6/execution';
import sse from 'k6/x/sse';
import { check, fail } from 'k6';
import { Counter, Trend } from 'k6/metrics';
import { textSummary } from 'https://jslib.k6.io/k6-summary/0.1.0/index.js';

export const BASE_URL = __ENV.BASE_URL || 'http://localhost:8089';
export const OWNER_ID = 9000;
export const STORE_ID = 9000;
export const N = parseInt(__ENV.N || '100', 10); // 고객(구독자) 수. seed.sql의 N 이하여야 한다

export const customerId = (i) => 10000 + i; // i = 1..N

// 구독자 시나리오(per-vu-iterations, iterations: 1)에서 VU마다 다른 고객을 맡긴다.
// __VU는 시나리오 사이에 번호가 섞여 같은 고객이 두 번 접속할 수 있어 쓰지 않는다
export const myMemberId = () => customerId(exec.scenario.iterationInInstance + 1);

const JSON_HEADERS = (memberId) => ({
    'Content-Type': 'application/json',
    'X-Member-Id': String(memberId),
});

// 이벤트 이름별 수신 건수. 알림 이름은 NotificationType enum 그대로 온다
export const received = {
    connect: new Counter('sse_connect'),                    // 연결 성공 인사
    called: new Counter('sse_waiting_called'),              // WAITING_CALLED
    position: new Counter('sse_waiting_position_changed'),  // WAITING_POSITION_CHANGED
    other: new Counter('sse_other'),
};
export const eventsPerConnection = new Trend('events_per_connection'); // 연결 1개가 받은 알림 수(connect 제외)
export const callDuration = new Trend('owner_call_duration_ms', true); // 점주 /call 응답 시간

// 고객 i가 웨이팅을 신청한다. 같은 인원으로 재요청하면 200으로 기존 건이 돌아오므로 재실행에도 안전하다
export function createWaiting(i) {
    const res = http.post(`${BASE_URL}/api/user/waitings`,
        JSON.stringify({ storeId: STORE_ID, partyCount: 2 }),
        { headers: JSON_HEADERS(customerId(i)), tags: { name: 'create_waiting' } });
    if (res.status !== 201 && res.status !== 200) {
        fail(`웨이팅 신청 실패 member=${customerId(i)} status=${res.status} body=${res.body}`);
    }
    return { memberId: customerId(i), waitingId: res.json('waitingId') };
}

// 고객 1..n 전원 웨이팅 신청. setup()에서 쓴다
export function createWaitings(n) {
    const list = [];
    for (let i = 1; i <= n; i++) list.push(createWaiting(i));
    console.log(`웨이팅 ${list.length}건 준비 (waitingId ${list[0].waitingId} ~ ${list[list.length - 1].waitingId})`);
    return list;
}

// 점주가 waitingId를 호출한다. 호출 1회 = 대상 1명에게 WAITING_CALLED + ACTIVE 전원에게 WAITING_POSITION_CHANGED
export function ownerCall(waitingId) {
    const res = http.post(`${BASE_URL}/api/owner/waitings/${waitingId}/call`, null,
        { headers: JSON_HEADERS(OWNER_ID), tags: { name: 'owner_call' } });
    callDuration.add(res.timings.duration);
    check(res, { 'call 200': (r) => r.status === 200 }) ||
        console.error(`call 실패 waitingId=${waitingId} status=${res.status} body=${res.body}`);
    return res;
}

// 고객 memberId로 SSE를 열고 timeout 동안 받은 알림을 센다. timeout이 지나면 연결이 끊기고 돌아온다
// (서버 타임아웃·EventSource 재연결과 같은 효과). 돌아오는 값은 이 연결에서 받은 알림 수(connect 제외)
export function subscribe(memberId, timeout, onEvent) {
    let count = 0;
    const res = sse.open(`${BASE_URL}/api/user/notifications/subscribe?memberId=${memberId}`,
        { timeout, tags: { name: 'subscribe' } },
        (client) => {
            client.on('event', (ev) => {
                if (ev.name === 'connect') { received.connect.add(1); return; }
                count++;
                if (__ENV.DEBUG && ev.name !== 'WAITING_POSITION_CHANGED') console.log(`[${memberId}] ${ev.name} id=${ev.id} ${ev.data}`);
                if (ev.name === 'WAITING_CALLED') received.called.add(1);
                else if (ev.name === 'WAITING_POSITION_CHANGED') received.position.add(1);
                else received.other.add(1);
                if (onEvent) onEvent(ev);
            });
            client.on('error', (e) => {
                const msg = String(e && e.error ? e.error() : e);
                if (!/timeout|deadline|canceled|EOF/i.test(msg)) console.error(`sse 오류 member=${memberId}: ${msg}`);
                // xk6-sse는 읽기 오류(타임아웃 포함) 뒤에도 스스로 끝나지 않으므로 여기서 닫아야 돌아온다
                client.close();
            });
        });
    if (res && res.status && res.status !== 200) console.error(`subscribe 실패 member=${memberId} status=${res.status}`);
    eventsPerConnection.add(count);
    return count;
}

// 결과를 results/<exp>-<시각>.json 에 남기고 터미널에는 기본 요약을 찍는다
// extraCounters: 실험별 Counter 이름 목록. 결과 JSON의 extra에 건수로 들어간다
export function makeSummary(expName, describe, extraCounters = []) {
    return function (data) {
        const m = data.metrics;
        const count = (name) => (m[name] && m[name].values && m[name].values.count) || 0;
        const trend = (name) => (m[name] && m[name].values) || {};
        const extra = {};
        for (const name of extraCounters) extra[name] = count(name);
        const result = {
            experiment: expName,
            finishedAt: new Date().toISOString(),
            params: { BASE_URL, N, ...extraParams() },
            received: {
                connect: count('sse_connect'),
                waitingCalled: count('sse_waiting_called'),
                positionChanged: count('sse_waiting_position_changed'),
                other: count('sse_other'),
            },
            eventsPerConnection: trend('events_per_connection'),
            ownerCallDurationMs: trend('owner_call_duration_ms'),
            ownerCalls: count('owner_calls'),
            checks: m.checks ? m.checks.values : null,
            extra,
        };
        if (describe) result.conclusion = describe(result);
        const ts = result.finishedAt.replace(/[:.]/g, '-');
        return {
            [`scripts/notification-loadtest/results/${expName}-${ts}.json`]: JSON.stringify(result, null, 2),
            stdout: textSummary(data, { indent: ' ', enableColors: true }) + '\n' + formatConclusion(result),
        };
    };
}

function extraParams() {
    const keys = ['CALLS', 'CALL_RATE', 'SUBSCRIBE_SECONDS', 'RECONNECT_EVERY', 'LATE_START'];
    const out = {};
    for (const k of keys) if (__ENV[k] !== undefined) out[k] = __ENV[k];
    return out;
}

function formatConclusion(r) {
    const lines = [
        '==================== 결과 ====================',
        `수신: connect=${r.received.connect}, WAITING_CALLED=${r.received.waitingCalled}, ` +
        `WAITING_POSITION_CHANGED=${r.received.positionChanged}, 기타=${r.received.other}`,
    ];
    if (r.conclusion) lines.push(...[].concat(r.conclusion));
    lines.push('==============================================');
    return lines.join('\n');
}

export const ownerCalls = new Counter('owner_calls');

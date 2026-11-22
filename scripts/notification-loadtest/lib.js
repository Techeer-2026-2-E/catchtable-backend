'use k6 = 2.1.0';
'use k6 with k6/x/sse = 0.2.0';

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
const ownerCalls = new Counter('owner_calls');
const ownerAttempts = new Counter('owner_attempts');
const ownerFailures = new Counter('owner_failures');
const callStarted = new Trend('owner_started_at_ms');
const callFinished = new Trend('owner_finished_at_ms');
const opened = new Counter('measured_subscriptions_opened');
const closed = new Counter('measured_subscriptions_closed');
const openedAt = new Trend('measured_subscription_opened_at_ms');
const closedAt = new Trend('measured_subscription_closed_at_ms');
const subscriptionAttempts = new Counter('subscription_attempts');
const connectedVus = new Counter('subscribed_vus');
let vuHadConnection = false;

export function callThresholds(expected) {
    return { owner_calls: [`count==${expected}`], owner_failures: ['count==0'], checks: ['rate==1'] };
}

export function connectedWindow(start, defaultOwnerSeconds) {
    const owner = Number(__ENV.OWNER_SECONDS || defaultOwnerSeconds);
    const subscription = Number(__ENV.SUBSCRIBE_SECONDS || (start + owner + 3));
    if (!Number.isSafeInteger(owner) || owner <= 0 || !Number.isSafeInteger(subscription) || subscription <= start + owner) {
        throw new Error('구독 시간은 점주 시작 시각과 호출 구간을 합친 시간보다 길어야 한다');
    }
    return { owner, subscription };
}

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
    ownerAttempts.add(1);
    callStarted.add(Date.now());
    const res = http.post(`${BASE_URL}/api/owner/waitings/${waitingId}/call`, null,
        { headers: JSON_HEADERS(OWNER_ID), tags: { name: 'owner_call' } });
    callFinished.add(Date.now());
    const ok = check(res, { 'call 200': (r) => r.status === 200 });
    ownerCalls.add(ok ? 1 : 0);
    ownerFailures.add(ok ? 0 : 1);
    if (ok) {
        callDuration.add(res.timings.duration);
    } else {
        console.error(`call 실패 waitingId=${waitingId} status=${res.status}`);
    }
    return res;
}

// 고객 memberId로 SSE를 열고 timeout 동안 받은 알림을 센다. timeout이 지나면 연결이 끊기고 돌아온다
// (서버 타임아웃·EventSource 재연결과 같은 효과). 돌아오는 값은 이 연결에서 받은 알림 수(connect 제외)
export function subscribe(memberId, timeout, onEvent, measureWindow = false) {
    let count = 0;
    let joined = false;
    let disconnectTime;
    subscriptionAttempts.add(1);
    const res = sse.open(`${BASE_URL}/api/user/notifications/subscribe?memberId=${memberId}`,
        { timeout, tags: { name: 'subscribe' } },
        (client) => {
            client.on('event', (ev) => {
                if (ev.name === 'connect') {
                    if (!joined) {
                        joined = true;
                        if (!vuHadConnection) {
                            vuHadConnection = true;
                            connectedVus.add(1);
                        }
                        received.connect.add(1);
                        if (measureWindow) {
                            opened.add(1);
                            openedAt.add(Date.now());
                        }
                    }
                    return;
                }
                count++;
                if (__ENV.DEBUG && ev.name !== 'WAITING_POSITION_CHANGED') console.log(`[${memberId}] ${ev.name} id=${ev.id} ${ev.data}`);
                if (ev.name === 'WAITING_CALLED') received.called.add(1);
                else if (ev.name === 'WAITING_POSITION_CHANGED') received.position.add(1);
                else received.other.add(1);
                if (onEvent) onEvent(ev);
            });
            client.on('error', (e) => {
                if (measureWindow && joined && disconnectTime === undefined) disconnectTime = Date.now();
                const msg = String(e && e.error ? e.error() : e);
                if (!/timeout|deadline|canceled|EOF/i.test(msg)) console.error(`sse 오류 member=${memberId}: ${msg}`);
                // xk6-sse는 읽기 오류(타임아웃 포함) 뒤에도 스스로 끝나지 않으므로 여기서 닫아야 돌아온다
                client.close();
            });
        });
    if (measureWindow && joined) {
        closed.add(1);
        closedAt.add(disconnectTime === undefined ? Date.now() : disconnectTime);
    }
    check(joined, { 'SSE 연결 인사 확인': value => value });
    if (res && res.status && res.status !== 200) console.error(`subscribe 실패 member=${memberId} status=${res.status}`);
    eventsPerConnection.add(count);
    return count;
}

// 결과를 results/<exp>-<시각>.json 에 남기고 터미널에는 기본 요약을 찍는다
// extraCounters: 실험별 Counter 이름 목록. 결과 JSON의 extra에 건수로 들어간다
export function makeSummary(expName, describe, { expectedCalls, expectedConnections, expectedVus, extraCounters = [], measureWindow = false }) {
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
            expectedCalls,
            attempts: count('owner_attempts'),
            failedCalls: count('owner_failures'),
            droppedIterations: count('dropped_iterations'),
            subscriptionAttempts: count('subscription_attempts'),
            connectedVus: count('subscribed_vus'),
            checks: m.checks ? m.checks.values : null,
            extra,
        };
        const issues = [];
        if (result.ownerCalls !== expectedCalls || result.attempts !== expectedCalls || result.failedCalls || result.droppedIterations)
            issues.push('예정·시도·성공 호출 수가 다르거나 시작하지 못한 반복이 있음');
        if (result.checks && result.checks.fails > 0) issues.push('HTTP 또는 시나리오 검사 실패');
        if (result.subscriptionAttempts !== result.received.connect ||
            (expectedVus !== undefined && result.connectedVus !== expectedVus))
            issues.push('구독을 시도한 고객 또는 재접속의 연결 인사를 확인할 수 없음');
        if (expectedConnections !== undefined && result.received.connect !== expectedConnections)
            issues.push('확인한 SSE 구독 수가 예정과 다름');
        if (measureWindow) {
            const window = result.window = {
                opened: count('measured_subscriptions_opened'), closed: count('measured_subscriptions_closed'),
                lastOpen: trend('measured_subscription_opened_at_ms').max,
                firstClose: trend('measured_subscription_closed_at_ms').min,
                firstCall: trend('owner_started_at_ms').min, lastCall: trend('owner_finished_at_ms').max,
            };
            if (window.opened !== N || window.closed !== N ||
                ![window.lastOpen, window.firstClose, window.firstCall, window.lastCall].every(Number.isFinite) ||
                !(window.lastOpen <= window.firstCall && window.lastCall <= window.firstClose))
                issues.push('호출 전체 구간의 전원 접속을 확인할 수 없음');
        }
        result.valid = issues.length === 0;
        result.conclusion = result.valid ? describe(result) : issues.map(issue => `측정 불충분: ${issue}`);
        const ts = result.finishedAt.replace(/[:.]/g, '-');
        return {
            [`scripts/notification-loadtest/results/${expName}-${ts}.json`]: JSON.stringify(result, null, 2),
            stdout: textSummary(data, { indent: ' ', enableColors: true }) + '\n' + formatConclusion(result),
        };
    };
}

function extraParams() {
    const keys = ['CALLS', 'CALL_RATE', 'OWNER_SECONDS', 'SUBSCRIBE_SECONDS', 'RECONNECT_EVERY', 'LATE_START'];
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

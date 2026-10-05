// 시나리오 공통: 요청 보내기, 단계 구성, 단계별 결과 표
import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';
import { uuidv4 } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';
import { textSummary } from 'https://jslib.k6.io/k6-summary/0.1.0/index.js';

const BASE = __ENV.BASE_URL || 'http://host.docker.internal:8080';
const JSON_HEADERS = { headers: { 'Content-Type': 'application/json' } };

export const WARMUP_SECONDS = Number(__ENV.WARMUP_SECONDS || 10);
export const STAGE_SECONDS = Number(__ENV.STAGE_SECONDS || 30);
// 단계 사이 간격: 앞 단계에서 처리 중이던 요청이 끝날 때까지 다음 단계와 겹치지 않게
const GAP_SECONDS = 5;

const status503 = new Counter('status_503');
const statusOtherError = new Counter('status_other_error');

export function post(path, body) {
    return http.post(`${BASE}${path}`, JSON.stringify(body), JSON_HEADERS);
}

// 세션 1개 + 참여자 2명 (1:1 채팅처럼 두 사람이 번갈아 보냄)
export function createSession() {
    const sessionId = post('/sessions', { eventId: uuidv4() }).json('sessionId');
    const participantIds = ['A', 'B'].map((name) =>
        post(`/sessions/${sessionId}/join`, { eventId: uuidv4(), displayName: name }).json('participantId'));
    return { sessionId, participantIds };
}

export function sendMessage(sessionId, participantId) {
    const res = post(`/sessions/${sessionId}/events`, {
        eventId: uuidv4(),
        participantId,
        type: 'MESSAGE',
        payload: { content: 'hello' },
    });
    check(res, { '201': (r) => r.status === 201 });
    recordError(res);
}

const loggedStatuses = new Set();

function recordError(res) {
    if (res.status === 201) return;
    if (res.status === 503) {
        status503.add(1);
    } else {
        statusOtherError.add(1);
    }
    // 처음 보는 상태 코드는 VU마다 한 번만 본문을 남긴다 (어떤 에러인지 확인용)
    if (loggedStatuses.has(res.status)) return;
    loggedStatuses.add(res.status);
    console.warn(`status=${res.status} body=${res.body}`);
}

// 워밍업 + VU 수별 단계를 차례로 실행하는 options
export function stagedOptions(stageVus) {
    return {
        scenarios: buildScenarios(stageVus),
        thresholds: buildThresholds(stageVus),
        summaryTrendStats: ['avg', 'med', 'p(95)', 'p(99)', 'max'],
    };
}

function buildScenarios(stageVus) {
    const scenarios = {
        warmup: { executor: 'constant-vus', vus: 1, duration: `${WARMUP_SECONDS}s`, gracefulStop: '5s' },
    };
    let startTime = WARMUP_SECONDS + GAP_SECONDS;
    for (const vus of stageVus) {
        scenarios[`vu${vus}`] = {
            executor: 'constant-vus',
            vus,
            duration: `${STAGE_SECONDS}s`,
            startTime: `${startTime}s`,
            gracefulStop: '5s',
        };
        startTime += STAGE_SECONDS + GAP_SECONDS;
    }
    return scenarios;
}

// 단계별 지표가 요약에 따로 집계되도록 등록 (항상 통과하는 조건)
function buildThresholds(stageVus) {
    const thresholds = {};
    for (const vus of stageVus) {
        const tag = `{scenario:vu${vus}}`;
        thresholds[`http_reqs${tag}`] = ['count>=0'];
        thresholds[`http_req_duration${tag}`] = ['max>=0'];
        thresholds[`http_req_failed${tag}`] = ['rate>=0'];
        thresholds[`status_503${tag}`] = ['count>=0'];
        thresholds[`status_other_error${tag}`] = ['count>=0'];
    }
    return thresholds;
}

// 단계별 표를 기본 요약 뒤에 붙인다
export function stagedSummary(data, stageVus, title) {
    const rows = stageVus.map((vus) => stageRow(data, vus));
    return {
        stdout: `${textSummary(data, { indent: ' ', enableColors: true })}\n\n${title}\n${formatTable(rows)}\n`,
    };
}

function stageRow(data, vus) {
    // 한 번도 기록되지 않은 지표(예: 에러 0건)는 요약에 없을 수 있다
    const metric = (name) => data.metrics[`${name}{scenario:vu${vus}}`]?.values ?? {};
    const duration = metric('http_req_duration');
    const requests = metric('http_reqs').count ?? 0;
    return {
        vus,
        requests,
        rps: Math.round(requests / STAGE_SECONDS),
        medMs: round(duration.med),
        p95Ms: round(duration['p(95)']),
        p99Ms: round(duration['p(99)']),
        maxMs: round(duration.max),
        failedPct: round((metric('http_req_failed').rate ?? 0) * 100),
        status503: metric('status_503').count ?? 0,
        otherErrors: metric('status_other_error').count ?? 0,
    };
}

function round(value) {
    return Math.round(value * 100) / 100;
}

function formatTable(rows) {
    const header = ['VU', 'requests', 'req/s', 'med(ms)', 'p95(ms)', 'p99(ms)', 'max(ms)', 'failed%', '503', 'other'];
    const lines = rows.map((r) => [r.vus, r.requests, r.rps, r.medMs, r.p95Ms, r.p99Ms, r.maxMs, r.failedPct, r.status503, r.otherErrors]);
    return [header, ...lines].map((cols) => cols.map((c) => String(c).padStart(9)).join(' ')).join('\n');
}

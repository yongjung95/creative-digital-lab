// 시나리오 1: 한 세션 집중 (Hot Session)
// 같은 세션 1개에 VU를 1 → 5 → 10 → 20 → 50으로 늘리며 단계별 처리량, p95, 실패를 본다.
import { createSession, sendMessage, stagedOptions, stagedSummary } from './lib/common.js';

const STAGE_VUS = [1, 5, 10, 20, 50];

export const options = stagedOptions(STAGE_VUS);

export function setup() {
    const session = createSession();
    console.log(`sessionId=${session.sessionId} participantIds=${session.participantIds}`);
    return session;
}

// 모든 VU가 같은 세션에, 두 참여자 중 하나로 보낸다
export default function (session) {
    sendMessage(session.sessionId, session.participantIds[__VU % 2]);
}

export function handleSummary(data) {
    return stagedSummary(data, STAGE_VUS, '단계별 결과 (같은 세션 1개)');
}

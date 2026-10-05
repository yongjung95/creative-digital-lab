// 시나리오 2: 여러 세션 분산
// VU마다 자기 세션에만 보낸다. 세션끼리 Lock이 따로라 서로 기다리지 않을 때 서버 전체 처리량을 본다.
import { createSession, sendMessage, stagedOptions, stagedSummary } from './lib/common.js';

const STAGE_VUS = [10, 20, 50, 100];
// 가장 큰 단계의 VU 수만큼 만들어서 한 단계 안에서 두 VU가 같은 세션을 쓰지 않게 한다
const SESSION_COUNT = Math.max(...STAGE_VUS);

export const options = {
    ...stagedOptions(STAGE_VUS),
    // 세션 100개 + JOIN 200번을 setup에서 만든다
    setupTimeout: '120s',
};

export function setup() {
    const sessions = Array.from({ length: SESSION_COUNT }, () => createSession());
    console.log(`sessionIds=${sessions[0].sessionId}~${sessions[SESSION_COUNT - 1].sessionId}`);
    return sessions;
}

// VU 번호로 세션을 고르고, 반복마다 두 참여자가 번갈아 보낸다
export default function (sessions) {
    const session = sessions[(__VU - 1) % SESSION_COUNT];
    sendMessage(session.sessionId, session.participantIds[__ITER % 2]);
}

export function handleSummary(data) {
    return stagedSummary(data, STAGE_VUS, `단계별 결과 (세션 ${SESSION_COUNT}개에 분산)`);
}

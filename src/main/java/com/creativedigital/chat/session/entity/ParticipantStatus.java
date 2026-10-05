package com.creativedigital.chat.session.entity;

public enum ParticipantStatus {

    ACTIVE,
    DISCONNECTED,
    LEFT;

    // 잠깐 끊긴 참여자도 자리를 차지한다. LEFT만 자리를 비운 것으로 본다
    public boolean occupiesSeat() {
        return this != LEFT;
    }

}

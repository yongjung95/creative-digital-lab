package com.creativedigital.chat.common.time;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * DB에는 KST LocalDateTime으로 저장하고, API 입출력은 시간대가 붙은 OffsetDateTime(+09:00)으로 주고받는다.
 */
public final class KstTime {

    public static final ZoneOffset OFFSET = ZoneOffset.ofHours(9);

    private KstTime() {
    }

    public static OffsetDateTime toOffset(LocalDateTime kst) {
        if (kst == null) return null;
        return kst.atOffset(OFFSET);
    }

    // 클라이언트가 어느 시간대로 보내든 KST로 바꿔서 저장한다
    public static LocalDateTime toKst(OffsetDateTime time) {
        if (time == null) return null;
        return time.withOffsetSameInstant(OFFSET).toLocalDateTime();
    }

}

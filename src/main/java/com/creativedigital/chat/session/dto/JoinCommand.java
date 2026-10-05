package com.creativedigital.chat.session.dto;

import java.time.LocalDateTime;

public record JoinCommand(
    String eventId,
    String displayName,
    LocalDateTime occurredAt
) {

}

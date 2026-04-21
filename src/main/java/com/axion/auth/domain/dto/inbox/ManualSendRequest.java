package com.axion.auth.domain.dto.inbox;

import jakarta.validation.constraints.NotBlank;

public record ManualSendRequest(@NotBlank String text) {
}

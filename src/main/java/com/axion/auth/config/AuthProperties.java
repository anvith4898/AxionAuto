package com.axion.auth.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "axion.auth")
public record AuthProperties(
        @NotBlank String tokenSecret,
        @Min(60) long sessionTtlSeconds
) {
}

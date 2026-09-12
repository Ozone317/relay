package com.example.relay.common.ratelimit;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@ConfigurationProperties(prefix = "relay.password-reset.rate-limit")
@Component
public class PasswordResetRateLimiterProperties {

    private long cooldownSeconds = 60;

    private int emailHourlyCap = 5;

    private int ipHourlyCap = 20;
}

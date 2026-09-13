package com.example.relay.user.recovery;

import java.time.Duration;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@ConfigurationProperties(prefix = "relay.password-reset.cleanup")
@Component
public class PasswordResetTokenCleanupProperties {

    private Duration interval = Duration.ofDays(1);

    private Duration retention = Duration.ofDays(7);
}

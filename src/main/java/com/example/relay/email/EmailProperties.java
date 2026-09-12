package com.example.relay.email;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@ConfigurationProperties(prefix = "relay.email")
@Component
public class EmailProperties {

    private String senderEmail;

    private String senderName;

    private final Brevo brevo = new Brevo();

    @Getter
    @Setter
    public static class Brevo {

        private String apiKey;
    }

    @PostConstruct
    void validate() {
        if (!StringUtils.hasText(senderEmail)) {
            throw new IllegalStateException(
                    "relay.email.sender-email is required (set RELAY_EMAIL_SENDER_EMAIL in .env) - "
                            + "email sending cannot work without a configured sender address");
        }
        if (!StringUtils.hasText(senderName)) {
            throw new IllegalStateException(
                    "relay.email.sender-name is required (set RELAY_EMAIL_SENDER_NAME in .env)");
        }
        if (!StringUtils.hasText(brevo.getApiKey())) {
            throw new IllegalStateException(
                    "relay.email.brevo.api-key is required (set BREVO_API_KEY in .env) - failing fast "
                            + "here instead of discovering a blank key the first time an email actually "
                            + "needs sending");
        }
    }
}

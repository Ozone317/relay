package com.example.relay.email;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import com.example.relay.support.SharedPostgresContainer;

@Tag("integration")
@SpringBootTest
class EmailRendererEmailVerificationTest implements SharedPostgresContainer {

    @Autowired
    private EmailRenderer underTest;

    @Test
    void render_producesSubjectHtmlAndTextForEmailVerification() {
        RenderedEmail email = underTest.render(EmailTemplate.EMAIL_VERIFICATION,
                Map.of("verificationUrl", "https://app.relay.example/verify-email?token=abc123"));

        assertTrue(email.subject().length() > 0);
        assertTrue(email.html().contains("https://app.relay.example/verify-email?token=abc123"));
        assertTrue(email.text().contains("https://app.relay.example/verify-email?token=abc123"));
    }
}

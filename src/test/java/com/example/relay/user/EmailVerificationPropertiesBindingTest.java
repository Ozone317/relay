package com.example.relay.user;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Autowired;
import com.example.relay.support.SharedPostgresContainer;

@SpringBootTest
class EmailVerificationPropertiesBindingTest implements SharedPostgresContainer {

    @Autowired
    private EmailVerificationProperties underTest;

    @Test
    void tokenTtl_bindsFromConfiguredProperty() {
        assertEquals(Duration.ofHours(24), underTest.getTokenTtl());
    }

    @Test
    void baseUrl_bindsFromConfiguredProperty() {
        assertEquals("http://localhost:3000/verify-email", underTest.getBaseUrl());
    }
}

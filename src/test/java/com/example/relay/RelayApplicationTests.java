package com.example.relay;

import com.example.relay.support.SharedPostgresContainer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@Tag("integration")
@SpringBootTest
class RelayApplicationTests implements SharedPostgresContainer {

    @Test
    void contextLoads() {}
}

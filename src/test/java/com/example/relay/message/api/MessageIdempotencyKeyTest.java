package com.example.relay.message.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.relay.message.exception.InvalidIdempotencyKeyException;
import java.util.List;
import org.junit.jupiter.api.Test;

class MessageIdempotencyKeyTest {

    @Test
    void parseHeaderValues_returnsEmpty_whenHeaderIsAbsent() {
        assertThat(MessageIdempotencyKey.parseHeaderValues(null)).isEmpty();
        assertThat(MessageIdempotencyKey.parseHeaderValues(List.of())).isEmpty();
    }

    @Test
    void parseHeaderValues_preservesValidTokenAndCase() {
        assertThat(MessageIdempotencyKey.parseHeaderValues(List.of("A-z_09.~!#$%&'*+^`|-")))
                .hasValueSatisfying(key -> assertThat(key.value()).isEqualTo("A-z_09.~!#$%&'*+^`|-"));
    }

    @Test
    void constructor_acceptsMaximumLengthToken() {
        String token = "a".repeat(255);

        assertThat(new MessageIdempotencyKey(token).value()).isEqualTo(token);
    }

    @Test
    void parseHeaderValues_rejectsMultipleValues() {
        assertInvalid(List.of("first", "second"));
    }

    @Test
    void constructor_rejectsInvalidSyntax() {
        for (String value : List.of("", " ", " key", "key ", "key,other", "key/value", "key:value", "café",
                "key\nvalue", "key\u0001value", "a".repeat(256))) {
            assertInvalid(List.of(value));
        }
    }

    private void assertInvalid(List<String> values) {
        assertThatThrownBy(() -> MessageIdempotencyKey.parseHeaderValues(values))
                .isInstanceOf(InvalidIdempotencyKeyException.class);
    }
}

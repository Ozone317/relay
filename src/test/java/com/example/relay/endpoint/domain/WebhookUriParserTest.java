package com.example.relay.endpoint.domain;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class WebhookUriParserTest {

    private final WebhookUriParser parser = new WebhookUriParser();

    @ParameterizedTest
    @MethodSource("acceptedUris")
    void parse_acceptsAndNormalizesWebhookUris(String raw, String normalized, String host, int port,
            boolean literal) throws Exception {
        ParsedWebhookUri parsed = parser.parse(raw);

        assertEquals(normalized, parsed.normalizedUri().toString());
        assertEquals(host, parsed.normalizedHost());
        assertEquals(port, parsed.effectivePort());
        assertEquals(literal, parsed.isLiteral());
    }

    static Stream<Arguments> acceptedUris() {
        return Stream.of(
                Arguments.of("http://192.0.2.1/", "http://192.0.2.1/", "192.0.2.1", 80, true),
                Arguments.of("HTTPS://[2001:DB8::1]:65535/hook", "https://[2001:db8::1]:65535/hook",
                        "2001:db8::1", 65535, true),
                Arguments.of("HTTP://Example.COM", "http://example.com", "example.com", 80, false),
                Arguments.of("https://BÜCHER.Example/hook", "https://xn--bcher-kva.example/hook",
                        "xn--bcher-kva.example", 443, false),
                Arguments.of("https://example.com./hook", "https://example.com/hook", "example.com", 443, false),
                Arguments.of("http://example.com:1/hook", "http://example.com:1/hook", "example.com", 1, false),
                Arguments.of("https://example.com:65535/hook?sig=a%2Fb&x=1",
                        "https://example.com:65535/hook?sig=a%2Fb&x=1", "example.com", 65535, false),
                Arguments.of("http://example.com/a%2Fb?x=%2F", "http://example.com/a%2Fb?x=%2F", "example.com", 80,
                        false));
    }

    @ParameterizedTest
    @MethodSource("rejectedUris")
    void parse_rejectsInvalidWebhookUris(String raw) {
        assertThrows(InvalidWebhookUriException.class, () -> parser.parse(raw));
    }

    static Stream<String> rejectedUris() {
        return Stream.of(
                "http://2130706433/",
                "http://0177.0.0.1/",
                "http://0x7f000001/",
                "http://2001:db8::1/",
                "http://[fe80::1%25eth0]/",
                "http://user:pass@example.com/",
                "http://example.com/#fragment",
                "http://localhost/",
                "http://example.com:0/",
                "http://example.com:65536/",
                "ftp://example.com/");
    }

    @Test
    void parse_exposesCanonicalLiteralParsingWithoutResolver() {
        Optional<IpLiteral> literal = IpLiteral.parse("127.0.0.1");

        assertTrue(literal.isPresent());
        assertArrayEquals(new byte[] {127, 0, 0, 1}, literal.get().addressBytes());
        assertTrue(IpLiteral.parse("2001:db8::1").isPresent());
        assertFalse(IpLiteral.parse("example.com").isPresent());
    }

    @Test
    void parser_hasNoResolverDependency() {
        assertNotNull(WebhookUriParser.class.getDeclaredConstructors());
        assertEquals(1, WebhookUriParser.class.getDeclaredConstructors().length);
        assertEquals(0, WebhookUriParser.class.getDeclaredConstructors()[0].getParameterCount());
    }

    @Test
    void normalizeDnsHostnameExposesTaskACanonicalHostContract() {
        assertEquals("example.com", WebhookUriParser.normalizeDnsHostname("example.com"));
        assertEquals("xn--bcher-kva.example", WebhookUriParser.normalizeDnsHostname("BÜCHER.Example"));
        assertEquals("example.com", WebhookUriParser.normalizeDnsHostname("example.com."));
        assertThrows(InvalidWebhookUriException.class,
                () -> WebhookUriParser.normalizeDnsHostname("localhost"));
    }
}

package com.example.relay.email;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

class EmailPropertiesConfigurationKeysTest {

    @Test
    void theConfiguredKeysAndTheBindableFieldsAreTheSameSet() throws IOException {
        Set<String> configuredTopLevel = keysUnderPrefix("relay.email.").stream()
                .filter(key -> !key.startsWith("brevo.")).collect(Collectors.toCollection(TreeSet::new));
        Set<String> bindableTopLevel = bindableFieldNames(EmailProperties.class, Set.of("brevo"));

        Set<String> configuredBrevo = keysUnderPrefix("relay.email.brevo.");
        Set<String> bindableBrevo = bindableFieldNames(EmailProperties.Brevo.class, Set.of());

        assertEquals(relaxedAll(bindableTopLevel), relaxedAll(configuredTopLevel),
                () -> "relay.email.* (excluding brevo.*) " + configuredTopLevel + " does not match EmailProperties' "
                        + "bindable fields " + bindableTopLevel);
        assertEquals(relaxedAll(bindableBrevo), relaxedAll(configuredBrevo),
                () -> "relay.email.brevo.* " + configuredBrevo + " does not match EmailProperties.Brevo's bindable "
                        + "fields " + bindableBrevo);
    }

    @Test
    void theFileActuallyDeclaresSomeEmailKeys() throws IOException {
        assertTrue(!keysUnderPrefix("relay.email.").isEmpty(),
                "application.properties declares no relay.email.* keys at all");
    }

    private static Set<String> keysUnderPrefix(String prefix) throws IOException {
        Properties properties = new Properties();
        try (InputStream in =
                EmailPropertiesConfigurationKeysTest.class.getResourceAsStream("/application.properties")) {
            assertTrue(in != null, "application.properties not found on the test classpath");
            properties.load(in);
        }
        return properties.stringPropertyNames().stream().filter(key -> key.startsWith(prefix))
                .map(key -> key.substring(prefix.length())).collect(Collectors.toCollection(TreeSet::new));
    }

    private static Set<String> bindableFieldNames(Class<?> type, Set<String> excludeFieldNames) {
        return Arrays.stream(type.getDeclaredFields())
                .filter(field -> !field.isSynthetic() && !Modifier.isStatic(field.getModifiers()))
                .map(Field::getName).filter(name -> !excludeFieldNames.contains(name))
                .collect(Collectors.toCollection(TreeSet::new));
    }

    private static Set<String> relaxedAll(Set<String> names) {
        return names.stream().map(name -> name.replaceAll("[^A-Za-z0-9]", "").toLowerCase())
                .collect(Collectors.toCollection(TreeSet::new));
    }
}

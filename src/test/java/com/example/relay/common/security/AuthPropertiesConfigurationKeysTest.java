package com.example.relay.common.security;

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

/**
 * Guards application.properties and {@link AuthProperties} against drifting apart.
 *
 * <p>
 * {@link AuthPropertiesBindingTest} proves binding works, but it supplies its own properties, so it cannot see a key
 * that is misspelled in the real file. Nothing else can either: every value in application.properties is identical to
 * the field default, so a dead key changes no observable behaviour and the application boots perfectly happily without
 * it.
 *
 * <p>
 * This is not hypothetical for this codebase - the refresh-token cycle retired {@code
 * jwt.expiration-ms} precisely because configuration and code had drifted apart unnoticed.
 */
class AuthPropertiesConfigurationKeysTest {

    private static final String PREFIX = "relay.auth.";

    @Test
    void theConfiguredKeysAndTheBindableFieldsAreTheSameSet() throws IOException {
        Set<String> configured = keysUnderPrefixInApplicationProperties();
        Set<String> bindable = bindableFieldNames();

        // Compared in Spring's relaxed-binding form, so kebab-case in the file and camelCase on
        // the field are treated as the same name - only genuinely unbindable keys show up.
        Set<String> configuredRelaxed = relaxedAll(configured);
        Set<String> bindableRelaxed = relaxedAll(bindable);

        assertEquals(bindableRelaxed, configuredRelaxed,
                () -> "relay.auth.* in application.properties " + configured + " does not match the bindable fields "
                        + bindable + ". A configured key with no matching field is dead - it binds to nothing and is "
                        + "silently ignored. A field with no configured key is running on its default with nothing "
                        + "in configuration able to change it.");
    }

    @Test
    void theFileActuallyDeclaresSomeAuthKeys() throws IOException {
        // Stops the comparison above from passing vacuously if application.properties ever moves
        // or stops carrying auth keys, which would make both sides empty and the set equality
        // trivially true while proving nothing.
        assertTrue(!keysUnderPrefixInApplicationProperties().isEmpty(),
                "application.properties declares no relay.auth.* keys at all");
    }

    private static Set<String> keysUnderPrefixInApplicationProperties() throws IOException {
        Properties properties = new Properties();
        try (InputStream in =
                AuthPropertiesConfigurationKeysTest.class.getResourceAsStream("/application.properties")) {
            assertTrue(in != null, "application.properties not found on the test classpath");
            properties.load(in);
        }
        return properties.stringPropertyNames().stream().filter(key -> key.startsWith(PREFIX))
                .map(key -> key.substring(PREFIX.length())).collect(Collectors.toCollection(TreeSet::new));
    }

    private static Set<String> bindableFieldNames() {
        return Arrays.stream(AuthProperties.class.getDeclaredFields())
                .filter(field -> !field.isSynthetic() && !Modifier.isStatic(field.getModifiers())).map(Field::getName)
                .collect(Collectors.toCollection(TreeSet::new));
    }

    private static Set<String> relaxedAll(Set<String> names) {
        return names.stream().map(name -> name.replaceAll("[^A-Za-z0-9]", "").toLowerCase())
                .collect(Collectors.toCollection(TreeSet::new));
    }
}

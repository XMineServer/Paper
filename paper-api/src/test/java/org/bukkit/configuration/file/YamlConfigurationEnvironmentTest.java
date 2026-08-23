package org.bukkit.configuration.file;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.List;
import java.util.Map;
import org.bukkit.configuration.InvalidConfigurationException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

// XMine - подстановка переменных среды
public class YamlConfigurationEnvironmentTest {

    private static final Map<String, String> ENV = Map.of(
        "DB_HOST", "db.internal",
        "DB_PORT", "5432",
        "DB_SECRET", "hunter2",
        "FEATURE_ON", "true",
        "LEADING_ZERO", "0755",
        "EMPTY", ""
    );

    /**
     * A configuration whose environment is the map above, so the tests do not have to mutate the
     * real process environment (which Java cannot do anyway).
     */
    private static final class TestConfiguration extends YamlConfiguration {
        @Nullable
        @Override
        protected String environmentValue(@NotNull String name) {
            return ENV.get(name);
        }
    }

    private static TestConfiguration load(String yaml) throws InvalidConfigurationException {
        TestConfiguration configuration = new TestConfiguration();
        configuration.loadFromString(yaml);
        return configuration;
    }

    @Test
    public void testStringSubstitution() throws Exception {
        YamlConfiguration configuration = load("host: ${DB_HOST}\n");
        assertEquals("db.internal", configuration.getString("host"));
    }

    @Test
    public void testSubstitutionInsideALongerValue() throws Exception {
        YamlConfiguration configuration = load("url: jdbc:postgresql://${DB_HOST}:${DB_PORT}/xmine\n");
        assertEquals("jdbc:postgresql://db.internal:5432/xmine", configuration.getString("url"));
    }

    @Test
    public void testUnquotedNumberIsTyped() throws Exception {
        // The whole point of substituting on the node tree: "${DB_PORT}" behaves like the
        // integer it expands to, so getInt() works without the plugin knowing anything.
        YamlConfiguration configuration = load("port: ${DB_PORT}\n");
        assertEquals(5432, configuration.getInt("port"));
        assertEquals(Integer.valueOf(5432), configuration.get("port"));
    }

    @Test
    public void testQuotedNumberStaysAString() throws Exception {
        YamlConfiguration configuration = load("port: \"${DB_PORT}\"\n");
        assertEquals("5432", configuration.get("port"));
        // Bukkit's getInt() hands back the default for anything that is not a Number, so a
        // quoted reference is genuinely unusable as a number - and that is exactly why
        // substitution happens on the node tree rather than on the text: an unquoted
        // reference gets a real YAML int tag and testUnquotedNumberIsTyped() passes.
        assertEquals(0, configuration.getInt("port"));
    }

    @Test
    public void testBooleanIsTyped() throws Exception {
        YamlConfiguration configuration = load("enabled: ${FEATURE_ON}\n");
        assertEquals(Boolean.TRUE, configuration.get("enabled"));
        assertTrue(configuration.getBoolean("enabled"));
    }

    @Test
    public void testNonCanonicalNumberStaysAString() throws Exception {
        // "0755" is octal 493 in YAML 1.1. Retyping it would mean saving 493 back to a file the
        // operator wrote 0755 into, so it deliberately stays a string.
        YamlConfiguration configuration = load("mode: ${LEADING_ZERO}\n");
        assertEquals("0755", configuration.get("mode"));
    }

    @Test
    public void testNestedSectionsAndLists() throws Exception {
        YamlConfiguration configuration = load("""
            db:
              host: ${DB_HOST}
              port: ${DB_PORT}
            hosts:
              - ${DB_HOST}
              - literal
              - ${MISSING}
            """);
        assertEquals("db.internal", configuration.getString("db.host"));
        assertEquals(5432, configuration.getInt("db.port"));
        assertEquals(List.of("db.internal", "literal", "${MISSING}"), configuration.getStringList("hosts"));
    }

    @Test
    public void testUnsetVariableIsLeftVerbatim() throws Exception {
        YamlConfiguration configuration = load("host: ${NOT_SET}\n");
        assertEquals("${NOT_SET}", configuration.getString("host"));
    }

    @Test
    public void testDefaultValue() throws Exception {
        YamlConfiguration configuration = load("host: ${NOT_SET:-localhost}\nport: ${NOT_SET:-25565}\n");
        assertEquals("localhost", configuration.getString("host"));
        assertEquals(25565, configuration.getInt("port"));
    }

    @Test
    public void testEscape() throws Exception {
        YamlConfiguration configuration = load("message: $${DB_HOST}\n");
        assertEquals("${DB_HOST}", configuration.getString("message"));
    }

    @Test
    public void testPluginPlaceholdersSurvive() throws Exception {
        YamlConfiguration configuration = load("join: \"Welcome ${player} to ${server_name}!\"\n");
        assertEquals("Welcome ${player} to ${server_name}!", configuration.getString("join"));
    }

    @Test
    public void testKeysAreNotSubstituted() throws Exception {
        YamlConfiguration configuration = load("${DB_HOST}: value\n");
        assertEquals("value", configuration.getString("${DB_HOST}"));
    }

    @Test
    public void testSaveKeepsTheTemplate() throws Exception {
        // The requirement this whole design exists for: a plugin that rewrites its own config
        // must not freeze the expanded value - here a password - onto disk.
        TestConfiguration configuration = load("""
            db:
              host: ${DB_HOST}
              port: ${DB_PORT}
              password: ${DB_SECRET}
            hosts:
              - ${DB_HOST}
              - literal
            """);
        configuration.set("db.timeout", 30); // the plugin touches something unrelated

        String saved = configuration.saveToString();
        assertTrue(saved.contains("${DB_HOST}"), saved);
        assertTrue(saved.contains("${DB_PORT}"), saved);
        assertTrue(saved.contains("${DB_SECRET}"), saved);
        assertFalse(saved.contains("db.internal"), saved);
        assertFalse(saved.contains("5432"), saved);
        assertFalse(saved.contains("hunter2"), saved);
        assertTrue(saved.contains("timeout: 30"), saved);
    }

    @Test
    public void testSaveRoundTripsBackToTheSameValues() throws Exception {
        TestConfiguration first = load("db:\n  host: ${DB_HOST}\n  port: ${DB_PORT}\n");
        TestConfiguration second = load(first.saveToString());
        assertEquals("db.internal", second.getString("db.host"));
        assertEquals(5432, second.getInt("db.port"));
    }

    @Test
    public void testDeliberateChangeWins() throws Exception {
        // If the plugin actually changed the value, that change must be written - the template
        // is restored only where the expanded value is still untouched.
        TestConfiguration configuration = load("db:\n  host: ${DB_HOST}\n");
        configuration.set("db.host", "explicitly-set");

        String saved = configuration.saveToString();
        assertTrue(saved.contains("explicitly-set"), saved);
        assertFalse(saved.contains("${DB_HOST}"), saved);
    }

    @Test
    public void testDisabledPerConfiguration() throws Exception {
        TestConfiguration configuration = new TestConfiguration();
        configuration.options().substituteEnvironmentVariables(false);
        configuration.loadFromString("host: ${DB_HOST}\n");
        assertEquals("${DB_HOST}", configuration.getString("host"));
        assertTrue(configuration.saveToString().contains("${DB_HOST}"));
    }

    @Test
    public void testVariableSetToEmpty() throws Exception {
        YamlConfiguration configuration = load("value: \"${EMPTY}\"\n");
        assertEquals("", configuration.getString("value"));
    }

    @Test
    public void testCommentsSurviveSubstitution() throws Exception {
        TestConfiguration configuration = load("""
            # database settings
            db:
              # where it lives
              host: ${DB_HOST}
            """);
        assertEquals("db.internal", configuration.getString("db.host"));
        assertEquals(List.of("where it lives"), configuration.getComments("db.host"));

        String saved = configuration.saveToString();
        assertTrue(saved.contains("# where it lives"), saved);
        assertTrue(saved.contains("${DB_HOST}"), saved);
    }
}

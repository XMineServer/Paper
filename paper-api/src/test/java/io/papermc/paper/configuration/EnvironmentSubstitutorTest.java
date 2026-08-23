package io.papermc.paper.configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import java.util.Map;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

// XMine - подстановка переменных среды
public class EnvironmentSubstitutorTest {

    private static final UnaryOperator<String> ENV = Map.of(
        "HOST", "db.internal",
        "PORT", "5432",
        "EMPTY", "",
        "INDIRECT", "${HOST}"
    )::get;

    private static String substitute(String text) {
        return EnvironmentSubstitutor.substitute(text, ENV);
    }

    @Test
    public void testPlainSubstitution() {
        assertEquals("db.internal", substitute("${HOST}"));
        assertEquals("jdbc:postgresql://db.internal:5432/x", substitute("jdbc:postgresql://${HOST}:${PORT}/x"));
    }

    @Test
    public void testNothingToSubstitute() {
        // null means "keep the original object" - the fast path every ordinary value takes.
        assertNull(substitute("plain text"));
        assertNull(substitute(""));
        assertNull(substitute("costs $5"));
        assertNull(substitute("a $ b"));
    }

    @Test
    public void testUnsetVariableIsLeftVerbatim() {
        // Not the empty string, and not an exception: an unset variable has to stay visible.
        assertNull(substitute("${MISSING}"));
        assertNull(substitute("prefix-${MISSING}-suffix"));
    }

    @Test
    public void testVariableSetToEmptyExpandsToEmpty() {
        assertEquals("", substitute("${EMPTY}"));
        assertEquals("a-b", substitute("a-${EMPTY}b"));
    }

    @Test
    public void testDefaultValue() {
        assertEquals("fallback", substitute("${MISSING:-fallback}"));
        assertEquals("db.internal", substitute("${HOST:-fallback}"));
        // POSIX ":-" treats an empty value as unset.
        assertEquals("fallback", substitute("${EMPTY:-fallback}"));
        // An explicitly empty default is how you ask for an empty string.
        assertEquals("", substitute("${MISSING:-}"));
    }

    @Test
    public void testNestedDefault() {
        assertEquals("db.internal", substitute("${MISSING:-${HOST}}"));
        assertEquals("last", substitute("${MISSING:-${ALSO_MISSING:-last}}"));
        assertEquals("{literal}", substitute("${MISSING:-{literal}}"));
    }

    @Test
    public void testEscape() {
        assertEquals("${HOST}", substitute("$${HOST}"));
        assertEquals("${MISSING:-x}", substitute("$${MISSING:-x}"));
        assertEquals("db.internal and ${HOST}", substitute("${HOST} and $${HOST}"));
    }

    @Test
    public void testSubstitutedValuesAreNotRescanned() {
        // INDIRECT holds the literal text "${HOST}"; expanding it must stop there.
        assertEquals("${HOST}", substitute("${INDIRECT}"));
    }

    @Test
    public void testExistingPlaceholdersAreUntouched() {
        // The reason names are upper case only: plugin placeholders must survive unchanged.
        assertNull(substitute("Hello ${player}!"));
        assertNull(substitute("${player_name}"));
        assertNull(substitute("${1}"));
        assertNull(substitute("${}"));
        assertNull(substitute("${some.setting}"));
        assertNull(substitute("${HOST-WITH-DASH}"));
        assertNull(substitute("regex: \\$\\{.+?\\}"));
        assertNull(substitute("price [$]{1,2}"));
    }

    @Test
    public void testUnterminatedReferenceIsUntouched() {
        assertNull(substitute("${HOST"));
        assertNull(substitute("${"));
        assertNull(substitute("$"));
        assertNull(substitute("$$"));
    }
}

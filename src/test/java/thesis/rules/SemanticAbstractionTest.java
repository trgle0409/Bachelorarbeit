package thesis.rules;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link SemanticAbstraction}.
 *
 * <p>All methods are static and pure. The class replaces COBOL identifiers with
 * {@code VAR1, VAR2, ...} (stable within a single call / shared map), preserves
 * reserved keywords, operators and placeholders ({@code <N>}, {@code <S>}, ...).
 */
class SemanticAbstractionTest {

    // =====================================================================
    // abstractText(...)
    // =====================================================================
    @Nested
    @DisplayName("abstractText")
    class AbstractText {

        @Test
        @DisplayName("maps the first identifier to VAR1 and keeps operator + placeholder")
        void mapsIdentifierKeepsOperatorAndPlaceholder() {
            assertThat(SemanticAbstraction.abstractText("CUSTOMER-STATUS = <S>"))
                    .isEqualTo("VAR1 = <S>");
        }

        @Test
        @DisplayName("keeps reserved action keywords, maps only the target identifier")
        void keepsReservedKeywords() {
            assertThat(SemanticAbstraction.abstractText("MOVE <S> TO RESULT-CODE"))
                    .isEqualTo("MOVE <S> TO VAR1");
        }

        @Test
        @DisplayName("re-uses the same VAR for a repeated identifier within one string")
        void stableMappingWithinString() {
            assertThat(SemanticAbstraction.abstractText("WS-A = WS-B AND WS-A > <N>"))
                    .isEqualTo("VAR1 = VAR2 AND VAR1 > <N>");
        }

        @Test
        @DisplayName("a reserved-only string is returned unchanged")
        void reservedOnly() {
            assertThat(SemanticAbstraction.abstractText("MOVE")).isEqualTo("MOVE");
        }

        @Test
        @DisplayName("null / blank input returns null")
        void nullOrBlank() {
            assertThat(SemanticAbstraction.abstractText(null)).isNull();
            assertThat(SemanticAbstraction.abstractText("   ")).isNull();
        }

        @Test
        @DisplayName("hyphenated identifiers are kept as one atomic token")
        void hyphenatedIdentifierIsAtomic() {
            String out = SemanticAbstraction.abstractText("CUSTOMER-ACCOUNT-STATUS = <N>");
            // Single identifier -> single VAR1, not split on hyphens.
            assertThat(out).isEqualTo("VAR1 = <N>");
        }
    }

    // =====================================================================
    // abstractIf(...)
    // =====================================================================
    @Nested
    @DisplayName("abstractIf")
    class AbstractIf {

        @Test
        @DisplayName("uses a shared variable map across condition / then / else")
        void sharedMapAcrossParts() {
            SemanticAbstraction.AbstractRuleParts parts = SemanticAbstraction.abstractIf(
                    "CUSTOMER-STATUS = <S>", "MOVE <S> TO RESULT-CODE", null);

            // CUSTOMER-STATUS -> VAR1 (condition), RESULT-CODE -> VAR2 (then).
            assertThat(parts.predicate()).isEqualTo("VAR1 = <S>");
            assertThat(parts.thenText()).isEqualTo("MOVE <S> TO VAR2");
            assertThat(parts.elseText()).isNull();
        }

        @Test
        @DisplayName("identifiers shared between branches keep the same VAR")
        void sharedIdentifierBetweenBranches() {
            SemanticAbstraction.AbstractRuleParts parts = SemanticAbstraction.abstractIf(
                    "A = <N> AND B > <N>", "MOVE A TO C", "MOVE B TO C");

            // A->VAR1, B->VAR2, C->VAR3 (first seen in THEN).
            assertThat(parts.predicate()).isEqualTo("VAR1 = <N> AND VAR2 > <N>");
            assertThat(parts.thenText()).isEqualTo("MOVE VAR1 TO VAR3");
            assertThat(parts.elseText()).isEqualTo("MOVE VAR2 TO VAR3");
        }
    }

    // =====================================================================
    // abstractEvaluateRule(...)
    // =====================================================================
    @Nested
    @DisplayName("abstractEvaluateRule")
    class AbstractEvaluate {

        @Test
        @DisplayName("builds 'subject | WHEN | when' predicate with shared map")
        void buildsPredicate() {
            SemanticAbstraction.AbstractRuleParts parts = SemanticAbstraction.abstractEvaluateRule(
                    "SQLCODE", "= <N>", "PERFORM OK-ROUTINE", null);

            assertThat(parts.predicate()).isEqualTo("VAR1 | WHEN | = <N>");
            assertThat(parts.thenText()).isEqualTo("PERFORM VAR2");
            assertThat(parts.elseText()).isNull();
        }

        @Test
        @DisplayName("an identifier appearing in both subject and when re-uses the same VAR")
        void subjectAndWhenShareVar() {
            SemanticAbstraction.AbstractRuleParts parts = SemanticAbstraction.abstractEvaluateRule(
                    "BEISPIEL", "BEISPIEL = <S>", "CONTINUE", null);

            // BEISPIEL must be VAR1 in both subject and when.
            assertThat(parts.predicate()).isEqualTo("VAR1 | WHEN | VAR1 = <S>");
        }

        @Test
        @DisplayName("null subject / when collapse to empty sides around WHEN")
        void nullSides() {
            SemanticAbstraction.AbstractRuleParts parts = SemanticAbstraction.abstractEvaluateRule(
                    null, null, "CONTINUE", null);

            assertThat(parts.predicate()).isEqualTo(" | WHEN | ");
        }
    }
}
package thesis.rules;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import thesis.parser.ParseMode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link RuleNormalizer#normalizeEntity(CobolExtractedRule)}.
 *
 * <p>{@code RuleNormalizer} is a pure {@code @Component} with no collaborators,
 * so it is exercised directly. Expected normalised strings were derived from the
 * operator-normalisation and literal-generalisation rules in the source.
 */
class RuleNormalizerTest {

    private final RuleNormalizer normalizer = new RuleNormalizer();

    private static CobolExtractedRule.CobolExtractedRuleBuilder base() {
        return CobolExtractedRule.builder()
                .programId(1L)
                .parseMode(ParseMode.FULL)
                .startLine(1)
                .endLine(10);
    }

    // =====================================================================
    // IF rules
    // =====================================================================
    @Nested
    @DisplayName("IF rules")
    class IfRules {

        @Test
        @DisplayName("normalises relational words and string literals")
        void normalisesEqualToAndStringLiteral() {
            CobolExtractedRule in = base()
                    .ruleType(RuleKind.IF)
                    .conditionText("CUSTOMER-STATUS IS EQUAL TO 'A'")
                    .thenText("MOVE 'B' TO RESULT-CODE")
                    .build();

            CobolNormalizedRuleEntity out = normalizer.normalizeEntity(in);

            assertThat(out.getKind()).isEqualTo(RuleKind.IF);
            assertThat(out.getConditionNorm()).isEqualTo("CUSTOMER-STATUS = <S>");
            assertThat(out.getThenNorm()).isEqualTo("MOVE <S> TO RESULT-CODE");
            assertThat(out.getSubjectNorm()).isNull();
            assertThat(out.getWhenNorm()).isNull();
            assertThat(out.getHasElse()).isFalse();
            assertThat(out.getThenStmtCount()).isEqualTo(1);
            assertThat(out.getElseStmtCount()).isZero();
        }

        @Test
        @DisplayName("normalises GREATER THAN and numeric literals")
        void normalisesGreaterThanAndNumber() {
            CobolExtractedRule in = base()
                    .ruleType(RuleKind.IF)
                    .conditionText("WS-COUNT IS GREATER THAN 100")
                    .thenText("PERFORM SOME-PARA")
                    .build();

            CobolNormalizedRuleEntity out = normalizer.normalizeEntity(in);

            assertThat(out.getConditionNorm()).isEqualTo("WS-COUNT > <N>");
        }

        @Test
        @DisplayName("generalises figurative constants ZEROS/SPACES")
        void generalisesFigurativeConstants() {
            CobolExtractedRule in = base()
                    .ruleType(RuleKind.IF)
                    .conditionText("A = ZEROS AND B = SPACES")
                    .thenText("CONTINUE")
                    .build();

            CobolNormalizedRuleEntity out = normalizer.normalizeEntity(in);

            assertThat(out.getConditionNorm()).isEqualTo("A = <ZERO> AND B = <SPACE>");
        }

        @Test
        @DisplayName("keeps numeric suffixes that are part of an identifier")
        void keepsIdentifierNumericSuffix() {
            CobolExtractedRule in = base()
                    .ruleType(RuleKind.IF)
                    .conditionText("WS-FIELD-01 = 1")
                    .thenText("CONTINUE")
                    .build();

            CobolNormalizedRuleEntity out = normalizer.normalizeEntity(in);

            // WS-FIELD-01 keeps its suffix; the standalone 1 becomes <N>.
            assertThat(out.getConditionNorm()).isEqualTo("WS-FIELD-01 = <N>");
        }

        @Test
        @DisplayName("marks hasElse and counts else statements")
        void detectsElseBranch() {
            CobolExtractedRule in = base()
                    .ruleType(RuleKind.IF)
                    .conditionText("X = 1")
                    .thenText("MOVE 1 TO A")
                    .elseText("MOVE 2 TO B")
                    .build();

            CobolNormalizedRuleEntity out = normalizer.normalizeEntity(in);

            assertThat(out.getHasElse()).isTrue();
            assertThat(out.getElseNorm()).isEqualTo("MOVE <N> TO B");
            assertThat(out.getElseStmtCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("complexity = 1 + AND + OR + NOT + then/3 + else/3")
        void computesComplexity() {
            CobolExtractedRule in = base()
                    .ruleType(RuleKind.IF)
                    .conditionText("X = 5 OR Y = 10 AND Z NOT = 3")
                    .thenText("MOVE 5 TO X PERFORM Y DISPLAY 'Z'") // 3 statements -> +1
                    .build();

            CobolNormalizedRuleEntity out = normalizer.normalizeEntity(in);

            // base 1 + AND(1) + OR(1) + NOT(1) + then(3/3=1) + else(0) = 5
            assertThat(out.getThenStmtCount()).isEqualTo(3);
            assertThat(out.getComplexityScore()).isEqualTo(5);
        }

        @Test
        @DisplayName("a pure CONTINUE branch is preserved as CONTINUE")
        void pureContinueBranchPreserved() {
            CobolExtractedRule in = base()
                    .ruleType(RuleKind.IF)
                    .conditionText("X = 1")
                    .thenText("CONTINUE")
                    .build();

            CobolNormalizedRuleEntity out = normalizer.normalizeEntity(in);

            assertThat(out.getThenNorm()).isEqualTo("CONTINUE");
            // CONTINUE is not one of the counted action keywords.
            assertThat(out.getThenStmtCount()).isZero();
        }

        @Test
        @DisplayName("strips an inline COBOL comment marker from the predicate")
        void stripsInlineComment() {
            CobolExtractedRule in = base()
                    .ruleType(RuleKind.IF)
                    .conditionText("X = 1 *> this is a comment")
                    .thenText("CONTINUE")
                    .build();

            CobolNormalizedRuleEntity out = normalizer.normalizeEntity(in);

            assertThat(out.getConditionNorm()).isEqualTo("X = <N>");
            assertThat(out.getConditionNorm()).doesNotContain("comment");
        }
    }

    // =====================================================================
    // EVALUATE rules
    // =====================================================================
    @Nested
    @DisplayName("EVALUATE rules")
    class EvaluateRules {

        @Test
        @DisplayName("preserves subject and uses whenText as the condition")
        void preservesSubjectAndWhen() {
            CobolExtractedRule in = base()
                    .ruleType(RuleKind.EVALUATE)
                    .subjectText("SQLCODE")
                    .whenText("SQLCODE = 0")
                    .thenText("PERFORM OK")
                    .build();

            CobolNormalizedRuleEntity out = normalizer.normalizeEntity(in);

            assertThat(out.getKind()).isEqualTo(RuleKind.EVALUATE);
            assertThat(out.getSubjectNorm()).isEqualTo("SQLCODE");
            assertThat(out.getWhenNorm()).isEqualTo("SQLCODE = <N>");
            // For non-OTHER, conditionNorm follows whenNorm.
            assertThat(out.getConditionNorm()).isEqualTo("SQLCODE = <N>");
        }

        @Test
        @DisplayName("WHEN OTHER maps conditionNorm to OTHER")
        void whenOther() {
            CobolExtractedRule in = base()
                    .ruleType(RuleKind.EVALUATE)
                    .subjectText("WS-CODE")
                    .whenText("OTHER")
                    .thenText("PERFORM DEFAULT-PARA")
                    .build();

            CobolNormalizedRuleEntity out = normalizer.normalizeEntity(in);

            assertThat(out.getWhenNorm()).isEqualTo("OTHER");
            assertThat(out.getConditionNorm()).isEqualTo("OTHER");
        }
    }

    // =====================================================================
    // Defensive / safe-default behaviour
    // =====================================================================
    @Nested
    @DisplayName("safe defaults")
    class SafeDefaults {

        @Test
        @DisplayName("null ruleType defaults to IF")
        void nullRuleTypeDefaultsToIf() {
            CobolExtractedRule in = base()
                    .ruleType(null)
                    .conditionText("X = 1")
                    .build();

            CobolNormalizedRuleEntity out = normalizer.normalizeEntity(in);

            assertThat(out.getKind()).isEqualTo(RuleKind.IF);
        }

        @Test
        @DisplayName("null parseMode defaults to FULL")
        void nullParseModeDefaultsToFull() {
            CobolExtractedRule in = CobolExtractedRule.builder()
                    .programId(1L)
                    .parseMode(null)
                    .ruleType(RuleKind.IF)
                    .startLine(1).endLine(2)
                    .conditionText("X = 1")
                    .build();

            CobolNormalizedRuleEntity out = normalizer.normalizeEntity(in);

            assertThat(out.getParseMode()).isEqualTo(ParseMode.FULL);
        }

        @Test
        @DisplayName("null then/else produce zero counts and hasElse false")
        void nullBranches() {
            CobolExtractedRule in = base()
                    .ruleType(RuleKind.IF)
                    .conditionText("X = 1")
                    .thenText(null)
                    .elseText(null)
                    .build();

            CobolNormalizedRuleEntity out = normalizer.normalizeEntity(in);

            assertThat(out.getThenNorm()).isNull();
            assertThat(out.getElseNorm()).isNull();
            assertThat(out.getHasElse()).isFalse();
            assertThat(out.getThenStmtCount()).isZero();
            assertThat(out.getElseStmtCount()).isZero();
            assertThat(out.getComplexityScore()).isEqualTo(1);
        }
    }
}
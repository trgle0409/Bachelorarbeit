package thesis.rules;

import org.antlr.v4.runtime.CommonTokenStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import thesis.parser.ParseMode;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the core classification logic inside
 * {@link CobolRuleCandidateVisitor}.
 *
 * <p>The visitor's traversal relies on a real ANTLR parse tree, which is out of
 * scope for a pure unit test. The interesting, grammar-independent logic is the
 * rule-name classification ({@code isIf} / {@code isEvaluate}) that decides
 * whether a visited {@code ParserRuleContext} becomes an IF or EVALUATE
 * candidate. These private predicates are exercised via reflection.
 *
 * <p>The classification is based on the lower-cased simple class name of the
 * context, e.g. {@code IfStatementContext -> "ifstatementcontext"}.
 */
class CobolRuleCandidateVisitorTest {

    private CobolRuleCandidateVisitor newVisitor() {
        // tokens may be null for these predicate-only tests
        return new CobolRuleCandidateVisitor(1L, ParseMode.FULL, (CommonTokenStream) null);
    }

    private boolean isIf(CobolRuleCandidateVisitor v, String ruleName) throws Exception {
        Method m = CobolRuleCandidateVisitor.class.getDeclaredMethod("isIf", String.class);
        m.setAccessible(true);
        return (boolean) m.invoke(v, ruleName);
    }

    private boolean isEvaluate(CobolRuleCandidateVisitor v, String ruleName) throws Exception {
        Method m = CobolRuleCandidateVisitor.class.getDeclaredMethod("isEvaluate", String.class);
        m.setAccessible(true);
        return (boolean) m.invoke(v, ruleName);
    }

    @Nested
    @DisplayName("isIf")
    class IsIf {

        @Test
        @DisplayName("true for a rule name containing both 'if' and 'statement'")
        void matchesIfStatement() throws Exception {
            CobolRuleCandidateVisitor v = newVisitor();
            // simple name of Cobol85Parser.IfStatementContext, lower-cased
            assertThat(isIf(v, "ifstatementcontext")).isTrue();
        }

        @Test
        @DisplayName("false when only 'if' is present without 'statement'")
        void requiresStatement() throws Exception {
            CobolRuleCandidateVisitor v = newVisitor();
            assertThat(isIf(v, "ifthenelsecontext")).isFalse();
        }

        @Test
        @DisplayName("false for an unrelated rule")
        void falseForUnrelated() throws Exception {
            CobolRuleCandidateVisitor v = newVisitor();
            assertThat(isIf(v, "moveStatementcontext".toLowerCase())).isFalse();
            assertThat(isIf(v, "evaluatestatementcontext")).isFalse();
        }
    }

    @Nested
    @DisplayName("isEvaluate")
    class IsEvaluate {

        @Test
        @DisplayName("true for a rule name containing both 'evaluate' and 'statement'")
        void matchesEvaluateStatement() throws Exception {
            CobolRuleCandidateVisitor v = newVisitor();
            assertThat(isEvaluate(v, "evaluatestatementcontext")).isTrue();
        }

        @Test
        @DisplayName("false when only 'evaluate' is present without 'statement'")
        void requiresStatement() throws Exception {
            CobolRuleCandidateVisitor v = newVisitor();
            assertThat(isEvaluate(v, "evaluatephrasecontext")).isFalse();
        }

        @Test
        @DisplayName("an IF statement is not classified as EVALUATE and vice versa")
        void mutuallyExclusive() throws Exception {
            CobolRuleCandidateVisitor v = newVisitor();
            assertThat(isEvaluate(v, "ifstatementcontext")).isFalse();
            assertThat(isIf(v, "evaluatestatementcontext")).isFalse();
        }
    }

    @Nested
    @DisplayName("candidates()")
    class Candidates {

        @Test
        @DisplayName("is empty before any traversal")
        void emptyInitially() {
            assertThat(newVisitor().candidates()).isEmpty();
        }
    }
}
package thesis.rules;

import org.antlr.v4.runtime.CommonToken;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.Token;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import thesis.parser.ParseMode;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the grammar-independent helper logic inside
 * {@link CobolStructuredRuleVisitor}.
 *
 * <p>The visit entry points ({@code visitIfStatement}, {@code visitEvaluateStatement})
 * require a real ANTLR parse tree and token stream produced by the generated
 * Cobol85 grammar, which is an integration concern. This class instead targets
 * the many pure helper methods the extraction is built from — token scanning
 * ({@code findKeyword}, {@code joinTokens}), keyword/normalisation utilities
 * ({@code normKw}, {@code isActionKeyword}, {@code minPos}), and the text
 * builders ({@code splitAlso}, {@code joinWithAnd}, {@code buildEvaluatePredicate},
 * {@code stripInlineComment}, {@code safeTrim}, {@code indentSnippet},
 * {@code buildEvaluateContextSnippet}). All are reached via reflection.
 *
 * <p>NOTE: {@code CobolStructuredRuleVisitor} extends the generated
 * {@code de.ba.antlr.cobol85.Cobol85BaseVisitor}; instantiation therefore
 * requires the ANTLR-generated sources to be on the test classpath (they are,
 * in the normal project build).
 */
class CobolStructuredRuleVisitorTest {

    private CobolStructuredRuleVisitor newVisitor() {
        return new CobolStructuredRuleVisitor(1L, ParseMode.FULL, (CommonTokenStream) null);
    }

    // --- reflection helpers ------------------------------------------------

    private Object invoke(String name, Class<?>[] sig, Object... args) throws Exception {
        Method m = CobolStructuredRuleVisitor.class.getDeclaredMethod(name, sig);
        m.setAccessible(true);
        return m.invoke(newVisitor(), args);
    }

    private static Token tok(String text) {
        CommonToken t = new CommonToken(Token.DEFAULT_CHANNEL);
        t.setText(text);
        return t;
    }

    private static List<Token> tokens(String... texts) {
        List<Token> ts = new ArrayList<>();
        for (String s : texts) ts.add(tok(s));
        return ts;
    }

    // =====================================================================
    // normKw
    // =====================================================================
    @Nested
    @DisplayName("normKw")
    class NormKw {

        @Test
        @DisplayName("upper-cases and trims, strips trailing comma/semicolon")
        void normalises() throws Exception {
            assertThat(invoke("normKw", new Class[]{Token.class}, tok("  move,"))).isEqualTo("MOVE");
            assertThat(invoke("normKw", new Class[]{Token.class}, tok("perform;"))).isEqualTo("PERFORM");
        }

        @Test
        @DisplayName("a lone dot stays a dot")
        void dotStaysDot() throws Exception {
            assertThat(invoke("normKw", new Class[]{Token.class}, tok("."))).isEqualTo(".");
        }

        @Test
        @DisplayName("null token / null text yields empty string")
        void nullSafe() throws Exception {
            assertThat(invoke("normKw", new Class[]{Token.class}, new Object[]{null})).isEqualTo("");
        }
    }

    // =====================================================================
    // isActionKeyword
    // =====================================================================
    @Nested
    @DisplayName("isActionKeyword")
    class IsActionKeyword {

        @Test
        @DisplayName("recognises COBOL action verbs")
        void recognisesActions() throws Exception {
            for (String kw : new String[]{"MOVE", "PERFORM", "COMPUTE", "CALL", "DISPLAY", "EXEC"}) {
                assertThat((boolean) invoke("isActionKeyword", new Class[]{String.class}, kw))
                        .as(kw).isTrue();
            }
        }

        @Test
        @DisplayName("returns false for non-action tokens")
        void rejectsNonActions() throws Exception {
            assertThat((boolean) invoke("isActionKeyword", new Class[]{String.class}, "WHEN")).isFalse();
            assertThat((boolean) invoke("isActionKeyword", new Class[]{String.class}, "FOO")).isFalse();
        }
    }

    // =====================================================================
    // minPos
    // =====================================================================
    @Nested
    @DisplayName("minPos")
    class MinPos {

        @Test
        @DisplayName("returns the smallest value")
        void smallest() throws Exception {
            assertThat(invoke("minPos", new Class[]{int[].class}, (Object) new int[]{5, 2, 9})).isEqualTo(2);
        }

        @Test
        @DisplayName("returns -1 when all are Integer.MAX_VALUE")
        void allMax() throws Exception {
            int max = Integer.MAX_VALUE;
            assertThat(invoke("minPos", new Class[]{int[].class}, (Object) new int[]{max, max})).isEqualTo(-1);
        }
    }

    // =====================================================================
    // findKeyword / findKeywordInRange / joinTokens
    // =====================================================================
    @Nested
    @DisplayName("token scanning")
    class TokenScanning {

        @Test
        @DisplayName("findKeyword returns the index of the first matching keyword")
        void findKeyword() throws Exception {
            List<Token> ts = tokens("IF", "X", "=", "1", "THEN", "MOVE");
            assertThat(invoke("findKeyword", new Class[]{List.class, String.class}, ts, "THEN")).isEqualTo(4);
            assertThat(invoke("findKeyword", new Class[]{List.class, String.class}, ts, "ELSE")).isEqualTo(-1);
        }

        @Test
        @DisplayName("findKeywordInRange respects the [from,to] window")
        void findKeywordInRange() throws Exception {
            List<Token> ts = tokens("MOVE", "A", "MOVE", "B");
            // first MOVE in range [1,3] is at index 2
            assertThat(invoke("findKeywordInRange",
                    new Class[]{List.class, String.class, int.class, int.class},
                    ts, "MOVE", 1, 3)).isEqualTo(2);
        }

        @Test
        @DisplayName("joinTokens concatenates token texts with single spaces")
        void joinTokens() throws Exception {
            List<Token> ts = tokens("MOVE", "1", "TO", "X");
            assertThat(invoke("joinTokens", new Class[]{List.class, int.class, int.class}, ts, 0, 3))
                    .isEqualTo("MOVE 1 TO X");
        }

        @Test
        @DisplayName("joinTokens returns null when from > to")
        void joinTokensEmptyRange() throws Exception {
            List<Token> ts = tokens("A", "B");
            assertThat(invoke("joinTokens", new Class[]{List.class, int.class, int.class}, ts, 2, 1))
                    .isNull();
        }
    }

    // =====================================================================
    // splitAlso / joinWithAnd / buildEvaluatePredicate
    // =====================================================================
    @Nested
    @DisplayName("EVALUATE text builders")
    class EvaluateBuilders {

        @SuppressWarnings("unchecked")
        @Test
        @DisplayName("splitAlso splits on ' ALSO ' boundaries")
        void splitAlso() throws Exception {
            List<String> parts = (List<String>) invoke("splitAlso", new Class[]{String.class}, "A ALSO B ALSO C");
            assertThat(parts).containsExactly("A", "B", "C");
        }

        @SuppressWarnings("unchecked")
        @Test
        @DisplayName("splitAlso on a single item returns that item")
        void splitAlsoSingle() throws Exception {
            List<String> parts = (List<String>) invoke("splitAlso", new Class[]{String.class}, "ONLY");
            assertThat(parts).containsExactly("ONLY");
        }

        @SuppressWarnings("unchecked")
        @Test
        @DisplayName("splitAlso on null/blank returns empty list")
        void splitAlsoBlank() throws Exception {
            assertThat((List<String>) invoke("splitAlso", new Class[]{String.class}, new Object[]{null})).isEmpty();
            assertThat((List<String>) invoke("splitAlso", new Class[]{String.class}, "  ")).isEmpty();
        }

        @Test
        @DisplayName("joinWithAnd joins multiple items with ' AND '")
        void joinWithAnd() throws Exception {
            assertThat(invoke("joinWithAnd", new Class[]{List.class}, List.of("X", "Y"))).isEqualTo("X AND Y");
            assertThat(invoke("joinWithAnd", new Class[]{List.class}, List.of("X"))).isEqualTo("X");
            assertThat(invoke("joinWithAnd", new Class[]{List.class}, List.of())).isNull();
        }

        @Test
        @DisplayName("buildEvaluatePredicate pairs subjects with whens via '='")
        void buildEvaluatePredicate() throws Exception {
            assertThat(invoke("buildEvaluatePredicate", new Class[]{List.class, List.class},
                    List.of("S1", "S2"), List.of("W1", "W2")))
                    .isEqualTo("S1 = W1 AND S2 = W2");
            assertThat(invoke("buildEvaluatePredicate", new Class[]{List.class, List.class},
                    List.of("S"), List.of("W")))
                    .isEqualTo("S = W");
        }

        @Test
        @DisplayName("buildEvaluatePredicate returns null when either side is empty")
        void buildEvaluatePredicateEmpty() throws Exception {
            assertThat(invoke("buildEvaluatePredicate", new Class[]{List.class, List.class},
                    List.of(), List.of("W"))).isNull();
            assertThat(invoke("buildEvaluatePredicate", new Class[]{List.class, List.class},
                    List.of("S"), List.of())).isNull();
        }
    }

    // =====================================================================
    // stripInlineComment / safeTrim / indentSnippet / buildEvaluateContextSnippet
    // =====================================================================
    @Nested
    @DisplayName("snippet helpers")
    class SnippetHelpers {

        @Test
        @DisplayName("stripInlineComment removes a '*>' comment tail")
        void stripsCommentTail() throws Exception {
            assertThat(invoke("stripInlineComment", new Class[]{String.class}, "MOVE 1 TO X *> note"))
                    .isEqualTo("MOVE 1 TO X");
        }

        @Test
        @DisplayName("stripInlineComment normalises EXEC BLOCK STUB variants")
        void normalisesExecStub() throws Exception {
            assertThat(invoke("stripInlineComment", new Class[]{String.class}, "DISPLAY \"EXEC BLOCK STUB\""))
                    .isEqualTo("EXEC_BLOCK_STUB");
        }

        @Test
        @DisplayName("stripInlineComment returns null for null / comment-only input")
        void nullForEmpty() throws Exception {
            assertThat(invoke("stripInlineComment", new Class[]{String.class}, new Object[]{null})).isNull();
            assertThat(invoke("stripInlineComment", new Class[]{String.class}, "*> only a comment")).isNull();
        }

        @Test
        @DisplayName("safeTrim returns null for blank, trimmed text otherwise")
        void safeTrim() throws Exception {
            assertThat(invoke("safeTrim", new Class[]{String.class}, "   ")).isNull();
            assertThat(invoke("safeTrim", new Class[]{String.class}, new Object[]{null})).isNull();
            assertThat(invoke("safeTrim", new Class[]{String.class}, "  hi  ")).isEqualTo("hi");
        }

        @Test
        @DisplayName("indentSnippet prefixes every line with the indentation")
        void indentSnippet() throws Exception {
            assertThat(invoke("indentSnippet", new Class[]{String.class, String.class}, "A\nB", "  "))
                    .isEqualTo("  A\n  B");
        }

        @Test
        @DisplayName("buildEvaluateContextSnippet wraps a branch with EVALUATE subject / END-EVALUATE")
        void buildsContextSnippet() throws Exception {
            String out = (String) invoke("buildEvaluateContextSnippet",
                    new Class[]{String.class, String.class},
                    "WS-X", "WHEN OTHER MOVE \"F\" TO Y");

            assertThat(out).isEqualTo(
                    "EVALUATE WS-X\n    WHEN OTHER MOVE \"F\" TO Y\nEND-EVALUATE");
        }

        @Test
        @DisplayName("buildEvaluateContextSnippet falls back to the branch when subject is missing")
        void contextFallbackNoSubject() throws Exception {
            String out = (String) invoke("buildEvaluateContextSnippet",
                    new Class[]{String.class, String.class}, null, "WHEN OTHER X");
            assertThat(out).isEqualTo("WHEN OTHER X");
        }

        @Test
        @DisplayName("buildEvaluateContextSnippet returns null when the branch is blank")
        void contextNullForBlankBranch() throws Exception {
            assertThat(invoke("buildEvaluateContextSnippet",
                    new Class[]{String.class, String.class}, "WS-X", "   ")).isNull();
        }
    }

    // =====================================================================
    // rules()
    // =====================================================================
    @Nested
    @DisplayName("rules()")
    class Rules {

        @Test
        @DisplayName("is empty before any traversal")
        void emptyInitially() {
            assertThat(newVisitor().rules()).isEmpty();
        }
    }
}
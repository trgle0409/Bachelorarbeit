package thesis.rules;

import de.ba.antlr.cobol85.Cobol85Lexer;
import de.ba.antlr.cobol85.Cobol85Parser;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.tree.ParseTree;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import thesis.parser.ParseMode;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test that exercises the full tree-walking path:
 * real {@code Cobol85Lexer}/{@code Cobol85Parser}
 * → {@link CobolRuleCandidateVisitor} / {@link CobolStructuredRuleVisitor}.
 *
 * <p>Unlike the unit tests for these visitors (which reflect into private string
 * helpers), this test parses actual COBOL source through the generated ANTLR
 * grammar and asserts that the visitors discover the IF and EVALUATE constructs
 * and produce well-formed extraction results.
 *
 * <p>The source is already in grammar-ready form, so it is parsed directly with
 * the generated lexer/parser (the same {@code startRule} entry point used by the
 * production parser service) rather than routed through the physical
 * preprocessor.
 *
 * <p>Requires the ANTLR-generated sources ({@code de.ba.antlr.cobol85.*}) on the
 * test classpath — i.e. the normal project build where source generation has run.
 */
class CobolVisitorsIntegrationTest {

    /** One IF (with ELSE) and one EVALUATE (WHEN + WHEN OTHER). */
    private static final String SOURCE = """
            IDENTIFICATION DIVISION.
            PROGRAM-ID. SAMPLE.
            PROCEDURE DIVISION.
            MAIN-PARA.
                IF WS-STATUS = "A"
                    MOVE 1 TO WS-CODE
                ELSE
                    MOVE 2 TO WS-CODE
                END-IF
                EVALUATE WS-CODE
                    WHEN 1
                        DISPLAY "ONE"
                    WHEN OTHER
                        DISPLAY "OTHER"
                END-EVALUATE.
            """;

    private static ParseTree tree;
    private static CommonTokenStream tokens;
    private static final ParseMode mode = ParseMode.FULL;

    @BeforeAll
    static void parseOnce() {
        Cobol85Lexer lexer = new Cobol85Lexer(CharStreams.fromString(SOURCE));
        lexer.removeErrorListeners();

        tokens = new CommonTokenStream(lexer);
        tokens.fill();

        Cobol85Parser parser = new Cobol85Parser(tokens);
        parser.removeErrorListeners();

        tree = parser.startRule();

        assertThat(parser.getNumberOfSyntaxErrors())
                .as("sample COBOL should parse without syntax errors.%n"
                        + "--- source fed to parser ---%n%s%n---", SOURCE)
                .isZero();
        assertThat(tree).isNotNull();
    }

    // =====================================================================
    // CobolRuleCandidateVisitor
    // =====================================================================
    @Nested
    @DisplayName("CobolRuleCandidateVisitor on a real parse tree")
    class CandidateVisitor {

        @Test
        @DisplayName("discovers exactly one IF and one EVALUATE candidate")
        void discoversIfAndEvaluate() {
            CobolRuleCandidateVisitor visitor =
                    new CobolRuleCandidateVisitor(42L, mode, tokens);
            visitor.visit(tree);

            List<RuleCandidate> candidates = visitor.candidates();

            long ifCount = candidates.stream()
                    .filter(c -> c.ruleKind() == RuleKind.IF).count();
            long evalCount = candidates.stream()
                    .filter(c -> c.ruleKind() == RuleKind.EVALUATE).count();

            assertThat(ifCount).isEqualTo(1);
            assertThat(evalCount).isEqualTo(1);
            assertThat(candidates).hasSize(2);
        }

        @Test
        @DisplayName("each candidate carries program id, mode, lines and a snippet")
        void candidatesAreWellFormed() {
            CobolRuleCandidateVisitor visitor =
                    new CobolRuleCandidateVisitor(42L, mode, tokens);
            visitor.visit(tree);

            assertThat(visitor.candidates()).isNotEmpty();
            for (RuleCandidate c : visitor.candidates()) {
                assertThat(c.programId()).isEqualTo(42L);
                assertThat(c.parseMode()).isEqualTo(mode);
                assertThat(c.startLine()).isPositive();
                assertThat(c.endLine()).isGreaterThanOrEqualTo(c.startLine());
                assertThat(c.snippet()).isNotBlank();
            }
        }

        @Test
        @DisplayName("the IF candidate's snippet reflects the IF source")
        void ifSnippetContent() {
            CobolRuleCandidateVisitor visitor =
                    new CobolRuleCandidateVisitor(42L, mode, tokens);
            visitor.visit(tree);

            RuleCandidate ifCand = visitor.candidates().stream()
                    .filter(c -> c.ruleKind() == RuleKind.IF)
                    .findFirst()
                    .orElseThrow();

            String snippet = ifCand.snippet().toUpperCase();
            assertThat(snippet).contains("IF");
            assertThat(snippet).contains("WS-STATUS");
        }
    }

    // =====================================================================
    // CobolStructuredRuleVisitor
    // =====================================================================
    @Nested
    @DisplayName("CobolStructuredRuleVisitor on a real parse tree")
    class StructuredVisitor {

        private List<CobolExtractedRule> extract() {
            CobolStructuredRuleVisitor visitor =
                    new CobolStructuredRuleVisitor(42L, mode, tokens);
            visitor.visit(tree);
            return visitor.rules();
        }

        @Test
        @DisplayName("extracts an IF rule with condition, then and else text")
        void extractsIfRule() {
            CobolExtractedRule ifRule = extract().stream()
                    .filter(r -> r.getRuleType() == RuleKind.IF)
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("expected one IF rule"));

            assertThat(ifRule.getProgramId()).isEqualTo(42L);
            assertThat(ifRule.getParseMode()).isEqualTo(mode);

            assertThat(ifRule.getConditionText()).isNotNull();
            assertThat(ifRule.getConditionText().toUpperCase()).contains("WS-STATUS");

            assertThat(ifRule.getThenText()).isNotNull();
            assertThat(ifRule.getThenText().toUpperCase()).contains("MOVE");

            // The IF has an ELSE branch in the sample.
            assertThat(ifRule.getElseText()).isNotNull();
            assertThat(ifRule.getElseText().toUpperCase()).contains("MOVE");

            assertThat(ifRule.getStartLine()).isPositive();
            assertThat(ifRule.getEndLine()).isGreaterThanOrEqualTo(ifRule.getStartLine());
        }

        @Test
        @DisplayName("extracts EVALUATE branches, including a WHEN OTHER branch")
        void extractsEvaluateBranches() {
            List<CobolExtractedRule> evalRules = extract().stream()
                    .filter(r -> r.getRuleType() == RuleKind.EVALUATE)
                    .toList();

            // One rule per WHEN branch: WHEN 1 and WHEN OTHER -> 2 branches.
            assertThat(evalRules).hasSize(2);

            // The subject is preserved on each branch.
            assertThat(evalRules).allSatisfy(r ->
                    assertThat(r.getSubjectText()).isNotNull());

            // Exactly one branch is the WHEN OTHER branch.
            long otherBranches = evalRules.stream()
                    .filter(r -> Boolean.TRUE.equals(r.getIsWhenOther()))
                    .count();
            assertThat(otherBranches).isEqualTo(1);

            // The OTHER branch's condition is normalised to "OTHER".
            CobolExtractedRule otherRule = evalRules.stream()
                    .filter(r -> Boolean.TRUE.equals(r.getIsWhenOther()))
                    .findFirst().orElseThrow();
            assertThat(otherRule.getConditionText()).isEqualToIgnoringCase("OTHER");
        }

        @Test
        @DisplayName("every extracted rule keeps a raw snippet for traceability")
        void rulesHaveRawSnippet() {
            List<CobolExtractedRule> rules = extract();
            assertThat(rules).isNotEmpty();
            assertThat(rules).allSatisfy(r ->
                    assertThat(r.getRawSnippet()).isNotBlank());
        }
    }

    // =====================================================================
    // End-to-end through the normalizer + abstraction (optional sanity chain)
    // =====================================================================
    @Nested
    @DisplayName("extraction feeds normalisation and abstraction")
    class DownstreamChain {

        @Test
        @DisplayName("an extracted IF rule normalises and abstracts without error")
        void normalizeAndAbstractIfRule() {
            CobolStructuredRuleVisitor visitor =
                    new CobolStructuredRuleVisitor(42L, mode, tokens);
            visitor.visit(tree);

            CobolExtractedRule ifRule = visitor.rules().stream()
                    .filter(r -> r.getRuleType() == RuleKind.IF)
                    .findFirst().orElseThrow();

            // Normalise
            CobolNormalizedRuleEntity norm = new RuleNormalizer().normalizeEntity(ifRule);
            assertThat(norm.getKind()).isEqualTo(RuleKind.IF);
            assertThat(norm.getConditionNorm()).isNotNull();
            // The double-quoted literal "A" is generalised to <S>.
            assertThat(norm.getConditionNorm()).contains("<S>");
            assertThat(norm.getHasElse()).isTrue();

            // Abstract
            SemanticAbstraction.AbstractRuleParts parts = SemanticAbstraction.abstractIf(
                    norm.getConditionNorm(), norm.getThenNorm(), norm.getElseNorm());
            assertThat(parts.predicate()).contains("VAR1");

            // Canonical keys are deterministic and non-blank.
            String canonical = CanonicalKey.of(
                    norm.getKind(), norm.getConditionNorm(),
                    norm.getThenNorm(), norm.getElseNorm(),
                    Boolean.TRUE.equals(norm.getHasElse()));
            assertThat(canonical).isNotBlank();
        }
    }
}
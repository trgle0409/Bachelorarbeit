package thesis.parser;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import thesis.corpus.CobolProgram;
import thesis.corpus.CobolProgramRepository;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Unit tests for {@link CobolParserService#validateProgramWithModeAndSignals_PUBLIC}.
 *
 * <p>The service reads the program from disk ({@code program.getPath()}),
 * preprocesses it with a real {@link CobolPreprocessor}, and classifies it. The
 * branches that resolve <em>before</em> the ANTLR parser runs are fully
 * unit-testable without the generated grammar:
 * <ul>
 *   <li>{@code IO_ERROR} — the source file cannot be read,</li>
 *   <li>{@code EMPTY_AFTER_CLEAN} — preprocessing leaves no content,</li>
 *   <li>{@code SKIPPED_COPYBOOK} — a data-only fragment,</li>
 *   <li>{@code SKIPPED_FUNCTION_UNIT} — a FUNCTION-ID compilation unit.</li>
 * </ul>
 * The repositories are not used by this method, so they are mocked. A real
 * preprocessor is wired in because its classification output is what we assert.
 *
 * <p>The fully-VALID parse path is intentionally not covered here: it requires
 * the generated {@code Cobol85Lexer}/{@code Cobol85Parser} and belongs in an
 * integration test.
 */
class CobolParserServiceTest {

    private CobolParserService newService() {
        CobolProgramRepository programRepo = mock(CobolProgramRepository.class);
        CobolParseResultRepository resultRepo = mock(CobolParseResultRepository.class);
        return new CobolParserService(programRepo, resultRepo, new CobolPreprocessor());
    }

    private static CobolProgram programAt(Path path) {
        return CobolProgram.builder()
                .id(1L)
                .systemName("S")
                .path(path.toString())
                .sourceType("S")
                .build();
    }

    private static CobolProgram programAtRawPath(String rawPath) {
        return CobolProgram.builder()
                .id(1L).systemName("S").path(rawPath).sourceType("S").build();
    }

    @Nested
    @DisplayName("validateProgramWithModeAndSignals_PUBLIC")
    class Validate {

        @Test
        @DisplayName("IO_ERROR when the source file does not exist")
        void ioErrorForMissingFile() {
            CobolParserService service = newService();
            CobolProgram p = programAtRawPath("/this/path/does/not/exist-xyz.cbl");

            Validated vd = service.validateProgramWithModeAndSignals_PUBLIC(p);

            assertThat(vd).isNotNull();
            assertThat(vd.attempt().status()).isEqualTo(ParseStatus.IO_ERROR);
            assertThat(vd.attempt().mode()).isEqualTo(ParseMode.FULL);
            assertThat(vd.attempt().tree()).isNull();
            assertThat(vd.signals().ifCount()).isZero();
        }

        @Test
        @DisplayName("EMPTY_AFTER_CLEAN when the file contains only comments")
        void emptyAfterClean(@TempDir Path dir) throws Exception {
            Path f = dir.resolve("comments-only.cbl");
            Files.writeString(f, """
                                  * comment one
                                  * comment two
                                  * comment three
                                  """);
            CobolParserService service = newService();

            Validated vd = service.validateProgramWithModeAndSignals_PUBLIC(programAt(f));

            assertThat(vd.attempt().status()).isEqualTo(ParseStatus.EMPTY_AFTER_CLEAN);
            assertThat(vd.attempt().message()).contains("Empty after preprocessing");
        }

        @Test
        @DisplayName("SKIPPED_COPYBOOK for a data-only fragment with level numbers")
        void skippedCopybook(@TempDir Path dir) throws Exception {
            Path f = dir.resolve("copybook.cpy");
            Files.writeString(f, """
                                  01 CUSTOMER-RECORD.
                                     05 CUST-ID    PIC 9(5).
                                     05 CUST-NAME  PIC X(30).
                                     05 CUST-FLAG  PIC X.
                                  """);
            CobolParserService service = newService();

            Validated vd = service.validateProgramWithModeAndSignals_PUBLIC(programAt(f));

            assertThat(vd.attempt().status()).isEqualTo(ParseStatus.SKIPPED_COPYBOOK);
            assertThat(vd.attempt().tree()).isNull();
        }

        @Test
        @DisplayName("SKIPPED_FUNCTION_UNIT for a FUNCTION-ID compilation unit")
        void skippedFunctionUnit(@TempDir Path dir) throws Exception {
            Path f = dir.resolve("func.cbl");
            Files.writeString(f, """
                                  IDENTIFICATION DIVISION.
                                  FUNCTION-ID. MY-FUNC.
                                  PROCEDURE DIVISION.
                                  DISPLAY "INSIDE FUNCTION".
                                  """);
            CobolParserService service = newService();

            Validated vd = service.validateProgramWithModeAndSignals_PUBLIC(programAt(f));

            assertThat(vd.attempt().status()).isEqualTo(ParseStatus.SKIPPED_FUNCTION_UNIT);
            assertThat(vd.attempt().tree()).isNull();
        }

        @Test
        @DisplayName("a Validated result is never null and always carries an attempt")
        void neverNull(@TempDir Path dir) throws Exception {
            Path f = dir.resolve("any.cbl");
            Files.writeString(f, "* just a comment\n");
            CobolParserService service = newService();

            Validated vd = service.validateProgramWithModeAndSignals_PUBLIC(programAt(f));

            assertThat(vd).isNotNull();
            assertThat(vd.attempt()).isNotNull();
            assertThat(vd.attempt().status()).isNotNull();
            assertThat(vd.attempt().mode()).isNotNull();
        }
    }

    // =====================================================================
    // Private pure helpers: detectRuleSignals, countOccurrences, trunc, snippetAround
    // =====================================================================
    @Nested
    @DisplayName("private helpers (via reflection)")
    class PrivateHelpers {

        private Object invoke(String name, Class<?>[] sig, Object... args) throws Exception {
            Method m = CobolParserService.class.getDeclaredMethod(name, sig);
            m.setAccessible(true);
            return m.invoke(newService(), args);
        }

        private Object invokeStatic(String name, Class<?>[] sig, Object... args) throws Exception {
            Method m = CobolParserService.class.getDeclaredMethod(name, sig);
            m.setAccessible(true);
            return m.invoke(null, args);
        }

        @Test
        @DisplayName("detectRuleSignals counts IF and EVALUATE occurrences")
        void detectRuleSignals() throws Exception {
            String src = "MOVE 1 TO X IF A = 1 THEN EVALUATE B WHEN 1 IF C = 2 CONTINUE";
            RuleSignals s = (RuleSignals) invoke(
                    "detectRuleSignals", new Class[]{String.class}, src);

            // " IF " occurs twice (note: leading/trailing spaces required by impl).
            assertThat(s.ifCount()).isEqualTo(2);
            assertThat(s.hasIf()).isTrue();
            // "EVALUATE " occurs once.
            assertThat(s.evaluateCount()).isEqualTo(1);
            assertThat(s.hasEvaluate()).isTrue();
        }

        @Test
        @DisplayName("detectRuleSignals on null returns all-zero signals")
        void detectRuleSignalsNull() throws Exception {
            RuleSignals s = (RuleSignals) invoke(
                    "detectRuleSignals", new Class[]{String.class}, new Object[]{null});
            assertThat(s.hasIf()).isFalse();
            assertThat(s.hasEvaluate()).isFalse();
            assertThat(s.ifCount()).isZero();
            assertThat(s.evaluateCount()).isZero();
        }

        @Test
        @DisplayName("countOccurrences counts non-overlapping needles")
        void countOccurrences() throws Exception {
            int c = (int) invoke("countOccurrences",
                    new Class[]{String.class, String.class}, "a-b-c-d", "-");
            assertThat(c).isEqualTo(3);
        }

        @Test
        @DisplayName("trunc shortens long strings and appends ellipsis")
        void trunc() throws Exception {
            assertThat(invokeStatic("trunc", new Class[]{String.class, int.class}, "short", 10))
                    .isEqualTo("short");
            assertThat(invokeStatic("trunc", new Class[]{String.class, int.class}, "abcdefghij", 5))
                    .isEqualTo("ab...");
            assertThat(invokeStatic("trunc", new Class[]{String.class, int.class}, new Object[]{null, 5}))
                    .isNull();
        }

        @Test
        @DisplayName("snippetAround renders a windowed, line-numbered excerpt")
        void snippetAround() throws Exception {
            String content = "L1\nL2\nL3\nL4\nL5";
            String snip = (String) invokeStatic("snippetAround",
                    new Class[]{String.class, Integer.class, int.class}, content, 3, 1);
            // window around line 3 with radius 1 -> lines 2,3,4
            assertThat(snip).contains("L2").contains("L3").contains("L4");
            assertThat(snip).doesNotContain("L1").doesNotContain("L5");
        }

        @Test
        @DisplayName("snippetAround returns null for invalid line numbers")
        void snippetAroundInvalid() throws Exception {
            assertThat(invokeStatic("snippetAround",
                    new Class[]{String.class, Integer.class, int.class}, "L1\nL2", 0, 2)).isNull();
            assertThat(invokeStatic("snippetAround",
                    new Class[]{String.class, Integer.class, int.class}, new Object[]{null, 1, 2})).isNull();
        }
    }
}
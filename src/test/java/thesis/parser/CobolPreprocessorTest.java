package thesis.parser;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link CobolPreprocessor}.
 *
 * <p>The preprocessor is pure (no Spring / DB dependencies), so it is exercised
 * directly. The grammar's job is explicitly out of scope here; we only assert
 * the physical/textual transformations the preprocessor is responsible for, plus
 * the three classifier methods used by {@code CobolParserService}.
 */
class CobolPreprocessorTest {

    private final CobolPreprocessor pre = new CobolPreprocessor();

    // small helper: a syntactically-plausible free-format program shell
    private static String programShell(String procedureBody) {
        return """
               IDENTIFICATION DIVISION.
               PROGRAM-ID. TESTPROG.
               PROCEDURE DIVISION.
               """ + procedureBody + "\n";
    }

    // =====================================================================
    // preprocess(...) — basic contract
    // =====================================================================
    @Nested
    @DisplayName("preprocess: basic contract")
    class Basic {

        @Test
        @DisplayName("null / blank input yields empty content and a 'raw blank' reason")
        void blankInput() {
            PreprocessResult r1 = pre.preprocess(null);
            assertThat(r1.content()).isEmpty();
            assertThat(r1.looksNonCobol()).isFalse();
            assertThat(r1.reason()).isEqualTo("raw blank");

            PreprocessResult r2 = pre.preprocess("   \n\t  ");
            assertThat(r2.content()).isEmpty();
            assertThat(r2.reason()).isEqualTo("raw blank");
        }

        @Test
        @DisplayName("normalises CRLF and stray CR to LF")
        void normalisesLineEndings() {
            String raw = programShell("DISPLAY \"HI\".").replace("\n", "\r\n");
            PreprocessResult r = pre.preprocess(raw);
            assertThat(r.content()).doesNotContain("\r");
        }

        @Test
        @DisplayName("ensures the cleaned output ends with a period")
        void ensuresFinalPeriod() {
            // No trailing period on the last logical line.
            String raw = """
                         IDENTIFICATION DIVISION.
                         PROGRAM-ID. TESTPROG.
                         PROCEDURE DIVISION.
                         DISPLAY "NO DOT"
                         """;
            PreprocessResult r = pre.preprocess(raw);
            assertThat(r.content().stripTrailing()).endsWith(".");
        }

        @Test
        @DisplayName("a recognisable program is not flagged as non-COBOL")
        void recognisesCobol() {
            PreprocessResult r = pre.preprocess(programShell("DISPLAY \"HELLO\"."));
            assertThat(r.looksNonCobol()).isFalse();
            assertThat(r.reason()).isNull();
        }

        @Test
        @DisplayName("a non-COBOL blob is flagged with a weak-signal reason")
        void flagsNonCobol() {
            PreprocessResult r = pre.preprocess("the quick brown fox jumps over the lazy dog repeatedly today");
            assertThat(r.looksNonCobol()).isTrue();
            assertThat(r.reason()).isEqualTo("Weak COBOL signal");
        }

        @Test
        @DisplayName("HTML content is detected and flagged")
        void detectsHtml() {
            PreprocessResult r = pre.preprocess(
                    "<html><body>IDENTIFICATION DIVISION. PROGRAM-ID. X.</body></html>");
            assertThat(r.looksNonCobol()).isTrue();
            assertThat(r.reason()).isEqualTo("Looks like HTML");
        }
    }

    // =====================================================================
    // preprocess(...) — line / token transformations
    // =====================================================================
    @Nested
    @DisplayName("preprocess: transformations")
    class Transformations {

        @Test
        @DisplayName("comment lines (leading '*') are removed")
        void stripsCommentLines() {
            String raw = """
                         IDENTIFICATION DIVISION.
                         PROGRAM-ID. TESTPROG.
                         PROCEDURE DIVISION.
                         * this is a comment line
                         DISPLAY "KEEP".
                         """;
            PreprocessResult r = pre.preprocess(raw);
            assertThat(r.content()).doesNotContain("this is a comment line");
            assertThat(r.content()).contains("DISPLAY");
        }

        @Test
        @DisplayName("EXEC ... END-EXEC blocks inside PROCEDURE DIVISION become an EXEC_BLOCK_STUB display")
        void stubsExecBlocksInProcedure() {
            String raw = """
                         IDENTIFICATION DIVISION.
                         PROGRAM-ID. TESTPROG.
                         PROCEDURE DIVISION.
                         EXEC SQL SELECT 1 FROM DUAL END-EXEC.
                         DISPLAY "AFTER".
                         """;
            PreprocessResult r = pre.preprocess(raw);
            assertThat(r.content()).contains("EXEC_BLOCK_STUB");
            // The raw SQL text must not leak through to the parser.
            assertThat(r.content()).doesNotContain("SELECT 1 FROM DUAL");
        }

        @Test
        @DisplayName("COPY directive inside PROCEDURE DIVISION is replaced by CONTINUE.")
        void copyInProcedureBecomesContinue() {
            String raw = """
                         IDENTIFICATION DIVISION.
                         PROGRAM-ID. TESTPROG.
                         PROCEDURE DIVISION.
                         COPY SOMECOPY.
                         DISPLAY "AFTER".
                         """;
            PreprocessResult r = pre.preprocess(raw);
            assertThat(r.content()).contains("CONTINUE");
            assertThat(r.content()).doesNotContain("SOMECOPY");
        }

        @Test
        @DisplayName("COPY-like identifiers (COPY-STATUS) are NOT treated as COPY directives")
        void copyGuardDoesNotMatchIdentifiers() {
            String raw = programShell("MOVE 1 TO COPY-STATUS.");
            PreprocessResult r = pre.preprocess(raw);
            assertThat(r.content()).contains("COPY-STATUS");
        }

        @Test
        @DisplayName("compiler/vendor directive lines (PROCESS, CBL, >>) are dropped")
        void dropsCompilerDirectives() {
            String raw = """
                         PROCESS NOSEQ
                         CBL LIST
                         >>SOURCE FORMAT FREE
                         IDENTIFICATION DIVISION.
                         PROGRAM-ID. TESTPROG.
                         PROCEDURE DIVISION.
                         DISPLAY "X".
                         """;
            PreprocessResult r = pre.preprocess(raw);
            assertThat(r.content()).doesNotContain("NOSEQ");
            assertThat(r.content()).doesNotContain(">>SOURCE");
        }

        @Test
        @DisplayName("smart quotes and lexer-hostile characters are normalised")
        void normalisesUnsupportedChars() {
            // smart quotes around a literal, plus a hostile '@' and underscore.
            String raw = programShell("MOVE \u201cVAL\u201d TO WS_FIELD@.");
            PreprocessResult r = pre.preprocess(raw);
            assertThat(r.content()).doesNotContain("\u201c").doesNotContain("\u201d");
            assertThat(r.content()).doesNotContain("@");
            // underscore is mapped to hyphen
            assertThat(r.content()).doesNotContain("WS_FIELD");
            assertThat(r.content()).contains("WS-FIELD");
        }

        @Test
        @DisplayName("double dots are collapsed to a single dot")
        void collapsesDoubleDots() {
            String raw = programShell("DISPLAY \"X\"..");
            PreprocessResult r = pre.preprocess(raw);
            assertThat(r.content()).doesNotContain("..");
        }

        @Test
        @DisplayName("reference modification FIELD(a:b) is reduced to the base variable")
        void stripsReferenceModification() {
            String raw = programShell("MOVE WS-NAME(1:5) TO WS-OUT.");
            PreprocessResult r = pre.preprocess(raw);
            assertThat(r.content()).contains("WS-NAME");
            assertThat(r.content()).doesNotContain("(1:5)");
        }

        @Test
        @DisplayName("ASSIGN TO DYNAMIC <id> keeps the identifier, drops DYNAMIC")
        void stripsAssignToDynamic() {
            String raw = """
                         IDENTIFICATION DIVISION.
                         PROGRAM-ID. TESTPROG.
                         ENVIRONMENT DIVISION.
                         INPUT-OUTPUT SECTION.
                         FILE-CONTROL.
                         SELECT F ASSIGN TO DYNAMIC WS-FILENAME.
                         PROCEDURE DIVISION.
                         DISPLAY "X".
                         """;
            PreprocessResult r = pre.preprocess(raw);
            assertThat(r.content()).contains("ASSIGN TO WS-FILENAME");
            assertThat(r.content()).doesNotContain("ASSIGN TO DYNAMIC");
        }

        @Test
        @DisplayName("commas are converted to spaces")
        void convertsCommasToSpaces() {
            String raw = programShell("ADD A, B, C GIVING D.");
            PreprocessResult r = pre.preprocess(raw);
            assertThat(r.content()).doesNotContain(",");
        }
    }

    // =====================================================================
    // preprocess(...) — fixed-format handling
    // =====================================================================
    @Nested
    @DisplayName("preprocess: fixed format")
    class FixedFormat {

        @Test
        @DisplayName("sequence numbers in cols 1-6 are stripped, code area kept")
        void stripsSequenceArea() {
            // cols: "000100" + ' ' indicator + code
            String raw = String.join("\n",
                    "000100 IDENTIFICATION DIVISION.",
                    "000200 PROGRAM-ID. TESTPROG.",
                    "000300 PROCEDURE DIVISION.",
                    "000400     DISPLAY \"FIXED\".");
            PreprocessResult r = pre.preprocess(raw);
            assertThat(r.content()).doesNotContain("000100");
            assertThat(r.content()).doesNotContain("000400");
            assertThat(r.content()).contains("DISPLAY");
            assertThat(r.content()).contains("PROCEDURE DIVISION");
        }

        @Test
        @DisplayName("fixed-format comment lines (indicator '*') are removed")
        void removesFixedFormatComments() {
            String raw = String.join("\n",
                    "000100 IDENTIFICATION DIVISION.",
                    "000150* a fixed-format comment",
                    "000200 PROGRAM-ID. TESTPROG.",
                    "000300 PROCEDURE DIVISION.",
                    "000400     DISPLAY \"X\".");
            PreprocessResult r = pre.preprocess(raw);
            assertThat(r.content()).doesNotContain("a fixed-format comment");
        }
    }

    // =====================================================================
    // tryExtractProcedureOnlyProgram(...)
    // =====================================================================
    @Nested
    @DisplayName("tryExtractProcedureOnlyProgram")
    class ProcedureOnly {

        @Test
        @DisplayName("returns null when there is no PROCEDURE DIVISION")
        void nullWhenNoProcedureDivision() {
            assertThat(pre.tryExtractProcedureOnlyProgram(
                    "IDENTIFICATION DIVISION. PROGRAM-ID. X.")).isNull();
            assertThat(pre.tryExtractProcedureOnlyProgram(null)).isNull();
            assertThat(pre.tryExtractProcedureOnlyProgram("  ")).isNull();
        }

        @Test
        @DisplayName("wraps the procedure body in a minimal valid program shell")
        void wrapsProcedureBody() {
            String cleaned = """
                             DATA DIVISION.
                             WORKING-STORAGE SECTION.
                             01 WS-X PIC 9.
                             PROCEDURE DIVISION.
                             DISPLAY "BODY".
                             """;
            String out = pre.tryExtractProcedureOnlyProgram(cleaned);

            assertThat(out).isNotNull();
            assertThat(out).contains("IDENTIFICATION DIVISION.");
            assertThat(out).contains("PROGRAM-ID. DUMMY.");
            assertThat(out).contains("PROCEDURE DIVISION.");
            assertThat(out).contains("DISPLAY \"BODY\".");
            // The corrupted DATA DIVISION must be dropped.
            assertThat(out).doesNotContain("WORKING-STORAGE");
        }
    }

    // =====================================================================
    // looksLikeCopybookOrDataFragment(...)
    // =====================================================================
    @Nested
    @DisplayName("looksLikeCopybookOrDataFragment")
    class Copybook {

        @Test
        @DisplayName("true for a data-only fragment starting with level numbers")
        void trueForLevelNumberFragment() {
            String frag = """
                          01 CUSTOMER-RECORD.
                             05 CUST-ID    PIC 9(5).
                             05 CUST-NAME  PIC X(30).
                          """;
            assertThat(pre.looksLikeCopybookOrDataFragment(frag)).isTrue();
        }

        @Test
        @DisplayName("false when a program header is present")
        void falseWhenProgramHeaderPresent() {
            String src = """
                         IDENTIFICATION DIVISION.
                         PROGRAM-ID. X.
                         DATA DIVISION.
                         01 WS-X PIC 9.
                         """;
            assertThat(pre.looksLikeCopybookOrDataFragment(src)).isFalse();
        }

        @Test
        @DisplayName("false for null / blank")
        void falseForBlank() {
            assertThat(pre.looksLikeCopybookOrDataFragment(null)).isFalse();
            assertThat(pre.looksLikeCopybookOrDataFragment("   ")).isFalse();
        }
    }

    // =====================================================================
    // looksLikeFunctionUnit(...)
    // =====================================================================
    @Nested
    @DisplayName("looksLikeFunctionUnit")
    class FunctionUnit {

        @Test
        @DisplayName("true for a FUNCTION-ID unit")
        void trueForFunctionId() {
            assertThat(pre.looksLikeFunctionUnit(
                    "FUNCTION-ID. MY-FUNC.\nPROCEDURE DIVISION.")).isTrue();
        }

        @Test
        @DisplayName("true for 'FUNCTION ID.' spelling")
        void trueForFunctionIdSpaced() {
            assertThat(pre.looksLikeFunctionUnit("FUNCTION ID. MY-FUNC.")).isTrue();
        }

        @Test
        @DisplayName("false for a normal program / blank")
        void falseForNormalProgram() {
            assertThat(pre.looksLikeFunctionUnit(
                    "IDENTIFICATION DIVISION. PROGRAM-ID. X.")).isFalse();
            assertThat(pre.looksLikeFunctionUnit(null)).isFalse();
            assertThat(pre.looksLikeFunctionUnit("  ")).isFalse();
        }
    }
}
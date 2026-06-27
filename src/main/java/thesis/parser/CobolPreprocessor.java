package thesis.parser;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Physical preprocessor for COBOL source files.
 *
 * Responsibility: transform raw COBOL text into a form that the ANTLR
 * Cobol85 grammar can parse without errors, while preserving as much
 * semantic content as possible for downstream rule extraction.
 *
 * Design notes — what this class does NOT do (grammar handles it):
 *   - referenceModifier  FIELD(pos:len)   → Cobol85.g4:2830 referenceModifier rule
 *   - DFHRESP(X)                          → cicsDfhRespLiteral  Cobol85.g4:3196
 *   - DFHVALUE(X)                         → cicsDfhValueLiteral Cobol85.g4:3200
 *   - double-quoted strings "..."         → STRINGLITERAL       Cobol85.g4:5483
 *   - comma as decimal separator 1,5      → NUMERICLITERAL      Cobol85.g4:5510
 *   - DISPLAY ... AT literal              → displayAt rule       Cobol85.g4:1727
 *   - comma separators between statements → SEPARATOR token      Cobol85.g4:5548
 *
 * What this class MUST handle (grammar does not support):
 *   - Fixed-format physical layout (col 7 indicator, cols 8-72, truncation)
 *   - Continuation lines (col 7 = '-')
 *   - EXEC...END-EXEC blocks (need *>EXECCICS tagging; we stub them instead)
 *   - COPY directives (need preprocessor grammar; we stub them)
 *   - REPLACE directives (need preprocessor grammar; we drop them)
 *   - Compiler directives: PROCESS, CBL, >>, COMPUWARE, etc.
 *   - CRT STATUS clause (not in Cobol85 grammar)
 *   - ASSIGN TO DYNAMIC (not in assignClause rule)
 *   - REPOSITORY / CLASS-ID (OO COBOL, not in Cobol85 grammar)
 *   - Double-dot ".." (ambiguous lexer token)
 *   - Non-printable control characters
 *   - Backtick / square bracket / exclamation mark (unknown to lexer)
 */
@Component
public class CobolPreprocessor {

    // -------------------------------------------------------------------------
    // Patterns
    // -------------------------------------------------------------------------

    /**
     * ASSIGN TO DYNAMIC <identifier> → ASSIGN TO <identifier>.
     * "DYNAMIC" is only valid in ACCESS MODE IS DYNAMIC (Cobol85.g4:370),
     * not as an assignment target in assignClause (Cobol85.g4:334).
     */
    private static final Pattern ASSIGN_TO_DYNAMIC = Pattern.compile(
            "\\bASSIGN\\s+TO\\s+DYNAMIC\\s+([A-Z0-9-]+)\\b",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern REFERENCE_MODIFICATION =
            Pattern.compile("\\b([A-Z0-9-]+)\\s*\\([^()\\n]*:[^()\\n]*\\)", Pattern.CASE_INSENSITIVE);

    private static final Pattern SUBSCRIPT =
            Pattern.compile("\\b([A-Z0-9-]+)\\s*\\([^():\\n]*\\)", Pattern.CASE_INSENSITIVE);

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    public PreprocessResult preprocess(String raw) {

        if (raw == null || raw.isBlank()) {
            return new PreprocessResult("", false, "raw blank");
        }

        // ── Step 1: Normalise line endings ────────────────────────────────────
        // Grammar NEWLINE rule (Cobol85.g4:5521) handles \r?\n, but normalising
        // first simplifies every subsequent line-by-line scan.
        String s = raw.replace("\r\n", "\n")
                .replace("\r",   "\n");


        // Tabs: WS rule (Cobol85.g4:5544) routes [ \t\f;]+ to HIDDEN channel,
        // so tabs are safe for the grammar.  We normalise them anyway so that
        // the column-counting logic in the fixed-format block below is reliable.
        s = s.replace("\t", "    ");

        // Normalize lexer-hostile characters before fixed-format processing.
        // This prevents BOM, smart quotes, braces, backslashes, etc. from
        // leaking into the ANTLR lexer.
        s = normalizeUnsupportedCharacters(s);

        boolean looksNonCobol = false;
        String  reason        = null;

        if (containsHtml(s)) {
            looksNonCobol = true;
            reason = "Looks like HTML";
        }

        // ── Step 2: Strip non-printable control characters ───────────────────
        // Grammar has no token for most control chars; they cause lexer errors.
        // Preserve \n (already normalised) and spaces.
        s = s.replaceAll("[\\p{Cntrl}&&[^\\n]]", " ");

        // ── Step 3: Line-by-line fixed-format processing ─────────────────────
        String[]      lines              = s.split("\n", -1);
        List<String>  processed          = new ArrayList<>();
        boolean       inExecBlock        = false;
        boolean       inProcedureDivision = false;
        boolean       pendingExecStubInProcedure = false;

        for (String line : lines) {
            String original = rtrim(line);
            if (original.isBlank()) continue;

            String upper = original.toUpperCase(Locale.ROOT);
            String lead  = upper.stripLeading();



            // ── Compiler / vendor directive lines ──────────────────────────
            // PROCESS / CBL: handled by Cobol85Preprocessor.g4 (not run here).
            // COMPUWARE, ANNA: vendor-specific, unknown to both grammars.
            if (lead.startsWith("PROCESS") ||
                    lead.startsWith("CBL") ||
                    isVendorDirectiveOrMetadata(upper, lead)) {
                continue;
            }

            // >>SOURCE FORMAT FREE and similar IBM/MicroFocus directives.
            // Neither grammar defines a '>>' token.
            if (lead.startsWith(">>")) {
                continue;
            }

            // REPLACE directives: handled by Cobol85Preprocessor.g4 replaceArea
            // rule.  Without that preprocessor, we must drop them to prevent the
            // "REPLACE ==" pseudo-text from leaking into the parser.
            if (isReplaceLine(lead)) {
                continue;
            }

            if (lead.startsWith("PROCEDURE DIVISION")) {
                inProcedureDivision = true;
            }
            // ── EXEC ... END-EXEC ───────────────────────────────────────────
            // Cobol85.g4 execCicsStatement (line 1841) expects EXECCICSLINE+
            // tokens that are produced only after Cobol85Preprocessor.g4 has
            // tagged each line with *>EXECCICS.  Without that tagging pass the
            // raw "EXEC CICS ..." text causes lexer errors, so we stub the block.
            if (!inExecBlock && upper.contains("EXEC ") && !upper.trim().startsWith("*>")) {
                inExecBlock = true;
                pendingExecStubInProcedure = inProcedureDivision;

                if (pendingExecStubInProcedure) {
                    processed.add("DISPLAY \"EXEC_BLOCK_STUB\"");
                }

                if (upper.contains("END-EXEC")) {
                    boolean hasTerminatingDot = upper.matches("(?s).*\\bEND-EXEC\\b\\s*\\..*");

                    if (pendingExecStubInProcedure && hasTerminatingDot) {
                        int last = processed.size() - 1;
                        if (last >= 0 && processed.get(last).equals("DISPLAY \"EXEC_BLOCK_STUB\"")) {
                            processed.set(last, "DISPLAY \"EXEC_BLOCK_STUB\".");
                        }
                    }

                    inExecBlock = false;
                    pendingExecStubInProcedure = false;
                }
                continue;
            }

            if (inExecBlock) {
                if (upper.contains("END-EXEC")) {
                    boolean hasTerminatingDot = upper.matches("(?s).*\\bEND-EXEC\\b\\s*\\..*");

                    if (pendingExecStubInProcedure && hasTerminatingDot) {
                        int last = processed.size() - 1;
                        if (last >= 0 && processed.get(last).equals("DISPLAY \"EXEC_BLOCK_STUB\"")) {
                            processed.set(last, "DISPLAY \"EXEC_BLOCK_STUB\".");
                        }
                    }

                    inExecBlock = false;
                    pendingExecStubInProcedure = false;
                }
                continue;
            }

            // ── COPY directives ─────────────────────────────────────────────
            // Cobol85Preprocessor.g4 handles COPY with full syntax (copyStatement
            // rule, line 237).  Without running that grammar we stub the line.
            // Guard: only match COPY as the first token, not identifiers like
            // COPY-STATUS or COPYBOOK-NAME.
            if (isCopyDirective(lead)) {
                if (inProcedureDivision) {
                    // COPY inside PROCEDURE DIVISION behaves like included statements.
                    // We do not want COPY itself to become a business rule action,
                    // but we need a valid no-op statement so the parser keeps the scope.
                    processed.add("CONTINUE.");
                }
                // COPY in DATA / ENVIRONMENT / LINKAGE divisions is dropped.
                // Replacing it with DISPLAY would be invalid outside PROCEDURE DIVISION.
                continue;
            }

            // ── Comment lines ───────────────────────────────────────────────
            if (original.stripLeading().startsWith("*")) {
                continue;
            }

            // ── Fixed-format physical layout ────────────────────────────────
// COBOL fixed format: cols 1-6 = sequence, col 7 = indicator,
// cols 8-72 = code area, cols 73-80 = identification (ignored).
            if (looksFixedFormatLine(original)) {
                char indicator = original.charAt(6);

                if (indicator == '*' || indicator == '/') {
                    continue; // comment indicator / page eject
                }

                String codeArea;
                if (original.length() >= 72) {
                    codeArea = original.substring(7, 72);
                } else if (original.length() > 7) {
                    codeArea = original.substring(7);
                } else {
                    codeArea = "";
                }
                codeArea = rtrim(codeArea);

                // Indicator '-' denotes a continuation of the immediately
                // preceding logical statement. Handle it before checking for
                // keywords such as COPY: within a continuation line these are
                // content, not directives.
                if (indicator == '-') {
                    appendFixedFormatContinuation(processed, codeArea);
                    continue;
                }

                String codeLead = codeArea.toUpperCase(Locale.ROOT).stripLeading();

                // A sequence-numbered fixed-format line such as
                // "000100 PROCEDURE DIVISION." is not recognised by the early
                // free-format check. Detect it here after extracting the code area.
                if (codeLead.startsWith("PROCEDURE DIVISION")) {
                    inProcedureDivision = true;
                }

                if (isCopyDirective(codeLead)) {
                    if (inProcedureDivision) {
                        processed.add("CONTINUE.");
                    }
                    continue;
                }

                if (isReplaceLine(codeLead) ||
                        codeLead.startsWith("PROCESS") ||
                        codeLead.startsWith("CBL") ||
                        codeLead.startsWith(">>") ||
                        isVendorDirective(codeLead, codeLead)) {
                    continue;
                }

                if (!codeArea.isBlank()) {
                    processed.add(codeArea);
                }
                continue;
            }

            // ── Free-format fallback ─────────────────────────────────────────
            processed.add(original);
        }

        String cleaned = String.join("\n", processed).trim();

        // COPY cleanup fallback.
        cleaned = cleaned.replaceAll("(?im)^\\s*COPY\\b.*\\.?\\s*$", "");
        cleaned = cleaned.replaceAll("(?im)^\\s*(\\d{2}\\s+[A-Z0-9-]+\\.?).*\\bCOPY\\b.*\\.?\\s*$", "$1");



        cleaned = cleaned.replaceAll("(?im)^.*\\bCOMPUWARE\\b.*$", "");
        cleaned = cleaned.replaceAll("(?im)^.*\\bANNA\\b.*$", "");
        cleaned = cleaned.replaceAll("(?im)^\\s*AUTHOR\\.?\\s+.*$", "");
        cleaned = cleaned.replaceAll("(?im)^\\s*INSTALLATION\\.?\\s+.*$", "");
        cleaned = cleaned.replaceAll("(?im)^\\s*REMARKS\\.?\\s+.*$", "");
        cleaned = cleaned.replaceAll("(?im)^\\s*SECURITY\\.?\\s+.*$", "");
        cleaned = cleaned.replaceAll("(?im)^\\s*DATE-WRITTEN\\.?\\s+.*$", "");
        cleaned = cleaned.replaceAll("(?im)^\\s*DATE-COMPILED\\.?\\s+.*$", "");

        // ACCEPT ... FROM ENVIRONMENT ...
        cleaned = cleaned.replaceAll(
                "(?im)(\\bACCEPT\\s+[A-Z0-9-]+)\\s*\\n\\s*FROM\\s+ENVIRONMENT\\s+(\"[^\"]*\"|'[^']*'|[A-Z0-9-]+)\\s*\\.?",
                "$1."
        );
        cleaned = cleaned.replaceAll(
                "(?im)\\bACCEPT\\s+([A-Z0-9-]+)\\s+FROM\\s+ENVIRONMENT\\s+(\"[^\"]*\"|'[^']*'|[A-Z0-9-]+)\\s*\\.?",
                "ACCEPT $1."
        );

        // SET ENVIRONMENT ...
        cleaned = cleaned.replaceAll(
        "(?im)^\\s*SET\\s+ENVIRONMENT\\b.*\\.?\\s*$", ""
        );

        // SPECIAL-NAMES cursor dialect.
        cleaned = cleaned.replaceAll(
                "(?im)^\\s*CURSOR\\s+IS\\b.*\\.?\\s*$",
                ""
        );

        cleaned = cleaned.replaceAll("(?m)^\\s*\\d{6}\\s*$", "");
        cleaned = cleaned.replaceAll("(?im)\\bFALSE\\s+IS\\b", "FALSE");
        cleaned = cleaned.replace(",", " ");

        cleaned = replaceUnsupportedStringUnstringBlocks(cleaned);

        cleaned = cleaned.replaceAll("(?m)^\\s*\\d{6}\\s*$", "");
        // ── Step 4: Token-level fixes ─────────────────────────────────────────
        // 4a) Double-dot: ".." is ambiguous — DOT_FS needs dot + whitespace,
        //     DOT is a single dot.  Two adjacent dots match neither cleanly.
        cleaned = cleaned.replaceAll("\\.{2,}", ".");

        // 4b) Page-eject: a bare "/" on its own line (legacy eject directive).
        //     Cobol85Preprocessor.g4 has ejectStatement (line 298) but we are
        //     not running that grammar.
        cleaned = cleaned.replaceAll("(?m)^\\s*/\\s*$", "");

        // 4c) Reference modification FIELD(start:length) and subscripts.
        // The grammar supports some variants, but dialectal/expression forms still
        // produce parser errors in the corpus. For rule extraction we keep the base
        // variable and remove positional access.
        cleaned = REFERENCE_MODIFICATION.matcher(cleaned).replaceAll("$1");
        // Subscript FIELD(index) / FIELD(i,j) -> FIELD.
        // Applied after reference modification so FIELD(start:length) is handled first.
        cleaned = SUBSCRIPT.matcher(cleaned).replaceAll("$1");

        // 4d) CRT STATUS clause: not present in Cobol85.g4 (SPECIAL-NAMES
        //     dialect extension).  Drop the entire line.
        cleaned = cleaned.replaceAll("(?im)^\\s*CRT\\s+STATUS\\b.*\\.?\\s*$", "");

        // 4e) ASSIGN TO DYNAMIC <id>: Cobol85.g4 assignClause (line 334) lists
        //     DISK | DISPLAY | ... | assignmentName | literal but NOT DYNAMIC as
        //     an assignment target (DYNAMIC appears only in ACCESS MODE clause).
        //     Strip the DYNAMIC keyword, keeping the identifier.
        cleaned = ASSIGN_TO_DYNAMIC.matcher(cleaned).replaceAll("ASSIGN TO $1");

        // 4f) REPOSITORY / CLASS-ID: OO COBOL extensions.  CLASS_ID token exists
        //     (Cobol85.g4:3518) but is not used in any parser rule.  Drop the
        //     lines to prevent unrecognised-token errors.
        cleaned = cleaned.replaceAll("(?im)^\\s*REPOSITORY\\b.*$", "");
        cleaned = cleaned.replaceAll("(?im)^\\s*CLASS-ID\\b.*$",   "");

        // 4g) Lexer-offending characters not defined in any grammar token:
        //       backtick → single quote (closest COBOL equivalent)
        //       [ ]      → spaces (sometimes used as array subscript in dialects)
        //       !        → space
        cleaned = cleaned.replace("`", "'")
                .replace("[", " ")
                .replace("]", " ")
                .replace("!", " ");

        // ── Step 5: Structural cleanup on whole source ────────────────────────
        // IMPORTANT: these must run before looksLikeCobol(cleaned).
        cleaned = removeConfigurationFunctionLines(cleaned);
        cleaned = removeScreenSection(cleaned);
        cleaned = insertMissingProgramId(cleaned);
        cleaned = insertMissingDataDivisionBeforeLevelNumbers(cleaned);


        // Final whitespace cleanup.
        cleaned = cleaned.replaceAll("(?m)[ \\t]+$", "");
        cleaned = cleaned.replaceAll("\\n{3,}", "\n\n").trim();

        // EOF protection: many fragments/programs miss the final period.
        cleaned = ensureFinalPeriod(cleaned);

        // ── Step 5: Sanity check ──────────────────────────────────────────────
        if (!looksLikeCobol(cleaned)) {
            looksNonCobol = true;
            if (reason == null) reason = "Weak COBOL signal";
        }

        return new PreprocessResult(cleaned, looksNonCobol, reason);
    }



    /**
     * Extracts the PROCEDURE DIVISION and wraps it in a minimal valid program
     * shell, enabling the parser to succeed on programs whose DATA DIVISION is
     * too corrupted to parse.  Returns null if no PROCEDURE DIVISION is found.
     */
    public String tryExtractProcedureOnlyProgram(String cleaned) {
        if (cleaned == null || cleaned.isBlank()) return null;

        String upper = cleaned.toUpperCase(Locale.ROOT);
        int idx = upper.indexOf("PROCEDURE DIVISION");
        if (idx < 0) return null;

        String proc = cleaned.substring(idx);

        return "       IDENTIFICATION DIVISION.\n"
                + "       PROGRAM-ID. DUMMY.\n"
                + "       PROCEDURE DIVISION.\n"
                + procAfterProcedureDivision(proc);
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private String procAfterProcedureDivision(String proc) {
        String u   = proc.toUpperCase(Locale.ROOT);
        int    kw  = u.indexOf("PROCEDURE DIVISION");
        int    dot = u.indexOf('.', kw);
        if (dot >= 0 && dot + 1 < proc.length()) {
            return proc.substring(dot + 1).stripLeading();
        }
        return proc;
    }

    /** True when the line starts a REPLACE directive (not a REPLACE OFF). */
    private boolean isReplaceLine(String upperLeadStripped) {
        // Match "REPLACE ==" or "REPLACE OFF" — both handled by preprocessor grammar
        return upperLeadStripped.matches("(?s)REPLACE\\b.*");
    }

    /**
     * True when the line is a COPY directive.
     * Guards against matching identifiers that start with "COPY"
     * (e.g. COPY-STATUS, COPYBOOK-NAME).
     */
    private boolean isCopyDirective(String upperLeadStripped) {
        // COPY followed by whitespace, dot, or end-of-string
        return upperLeadStripped.matches("COPY(\\s.*|\\..*|)");
    }

    private String normalizeUnsupportedCharacters(String s) {
        if (s == null || s.isEmpty()) {
            return s;
        }

        return s
                // UTF-8 BOM
                .replace("\uFEFF", " ")

                // Smart quotes -> normal quotes
                .replace("“", "\"")
                .replace("”", "\"")
                .replace("„", "\"")
                .replace("‘", "'")
                .replace("’", "'")

                // Lexer-hostile characters observed in parse diagnostics
                .replace("{", " ")
                .replace("}", " ")
                .replace("@", " ")
                .replace("?", " ")
                .replace("\\", " ")
                .replace("#", " ")
                .replace("_", "-");
    }

    private boolean containsHtml(String s) {
        String lower = s.toLowerCase(Locale.ROOT);
        return lower.contains("<html")   ||
                lower.contains("<script") ||
                lower.contains("</div>");
    }

    private boolean looksLikeCobol(String s) {
        String u = s.toUpperCase(Locale.ROOT);
        boolean hasSignal =
                u.contains("IDENTIFICATION")    ||
                u.contains("PROGRAM-ID")         ||
                u.contains("PROCEDURE DIVISION") ||
                u.contains("DATA DIVISION")      ||
                u.contains("WORKING-STORAGE")    ||
                u.contains("ENVIRONMENT DIVISION");
        long letters = s.chars().filter(Character::isLetter).count();
        return hasSignal && letters > 10;
    }

    private String rtrim(String x) {
        int i = x.length() - 1;
        while (i >= 0 && Character.isWhitespace(x.charAt(i))) i--;
        return x.substring(0, i + 1);
    }

    private boolean looksFixedFormatLine(String original) {
        if (original == null || original.length() < 7) return false;

        String seqArea = original.substring(0, 6);
        char indicator = original.charAt(6);

        boolean seqLooksFixed = seqArea.isBlank() || seqArea.matches("[ 0-9A-Za-z]{6}");
        boolean indicatorLooksFixed = indicator == ' ' || indicator == '-' || indicator == '*' || indicator == '/';

        return seqLooksFixed && indicatorLooksFixed;
    }

    private boolean isVendorDirective(String upperOriginal, String upperLead) {
        String x = upperLead.stripLeading();

        // Plain free format
        if (x.startsWith("COMPUWARE") || x.startsWith("ANNA")) {
            return true;
        }

        // Fixed/numbered style: optional sequence number before directive
        return x.matches("^[0-9A-Z]{1,6}\\s+(COMPUWARE|ANNA)\\b.*");
    }

    private boolean isVendorDirectiveOrMetadata(String upperOriginal, String upperLead) {
        String x = upperLead.stripLeading();

        if (x.startsWith("COMPUWARE") || x.startsWith("ANNA")) return true;

        // fixed-format / numbered prefix before vendor word
        if (x.matches("^[0-9A-Z]{1,6}\\s+(COMPUWARE|ANNA)\\b.*")) return true;

        // AUTHOR. COMPUWARE..., INSTALLATION. ANNA..., REMARKS. ...
        return x.matches("^(AUTHOR|INSTALLATION|REMARKS|SECURITY|DATE-WRITTEN|DATE-COMPILED)\\.?\\s+.*\\b(COMPUWARE|ANNA)\\b.*");
    }

    public boolean looksLikeCopybookOrDataFragment(String cleaned) {
        if (cleaned == null || cleaned.isBlank()) {
            return false;
        }

        String u = cleaned.stripLeading().toUpperCase(Locale.ROOT);

        boolean hasProgramHeader =
                u.contains("IDENTIFICATION DIVISION")
                        || u.contains("ID DIVISION")
                        || u.contains("PROGRAM-ID")
                        || u.contains("PROCEDURE DIVISION");

        if (hasProgramHeader) {
            return false;
        }

        String[] lines = u.split("\\R");
        int meaningful = 0;

        for (String line : lines) {
            String x = line.strip();

            if (x.isBlank()) continue;
            if (x.startsWith("*") || x.startsWith("*>")) continue;

            meaningful++;

            // Copybook/data-description entries usually start with level numbers.
            if (x.matches("^(01|02|03|04|05|10|15|20|49|66|77|88)\\b.*")) {
                return true;
            }

            // Some fixed-format leftovers can leave sequence columns before level numbers.
            if (x.matches("^[0-9A-Z]{1,6}\\s+(01|02|03|04|05|10|15|20|49|66|77|88)\\b.*")) {
                return true;
            }

            if (meaningful >= 8) {
                break;
            }
        }

        return false;
    }

    public boolean looksLikeFunctionUnit(String cleaned) {
        if (cleaned == null || cleaned.isBlank()) {
            return false;
        }

        String u = cleaned.toUpperCase(Locale.ROOT);

        return u.matches("(?s).*\\bFUNCTION-ID\\s*\\..*")
                || u.matches("(?s).*\\bFUNCTION\\s+ID\\s*\\..*")
                || u.matches("(?s).*\\bFUNCTION-ID\\b.*")
                || u.matches("(?s).*\\bFUNCTION\\s+ID\\b.*");
    }

    private String insertMissingDataDivisionBeforeLevelNumbers(String s) {
        if (s == null || s.isBlank()) return s;

        String[] lines = s.split("\\R", -1);
        List<String> out = new ArrayList<>();

        boolean hasDataDivision = s.toUpperCase(Locale.ROOT).contains("DATA DIVISION");
        boolean hasProcedureDivision = false;
        boolean afterProgramId = false;
        boolean inserted = false;

        for (String line : lines) {
            String u = line.stripLeading().toUpperCase(Locale.ROOT);

            if (u.startsWith("PROCEDURE DIVISION")) {
                hasProcedureDivision = true;
            }

            if (!hasDataDivision && !inserted && afterProgramId
                    && u.matches("^(01|02|03|04|05|10|15|20|49|66|77|88)\\b.*")) {
                out.add("       DATA DIVISION.");
                out.add("       WORKING-STORAGE SECTION.");
                inserted = true;
            }

            out.add(line);

            if (u.startsWith("PROGRAM-ID")) {
                afterProgramId = true;
            }
        }

        return String.join("\n", out);
    }

    private String removeScreenSection(String s) {
        if (s == null || s.isBlank()) return s;

        return s.replaceAll(
                "(?ims)^\\s*SCREEN\\s+SECTION\\s*\\.?.*?(?=^\\s*(PROCEDURE\\s+DIVISION|LINKAGE\\s+SECTION|FILE\\s+SECTION|WORKING-STORAGE\\s+SECTION|LOCAL-STORAGE\\s+SECTION)\\b)",
                ""
        );
    }

    private String removeConfigurationFunctionLines(String s) {
        if (s == null || s.isBlank()) return s;

        String[] lines = s.split("\\R", -1);
        List<String> out = new ArrayList<>();

        boolean inConfiguration = false;

        for (String line : lines) {
            String u = line.stripLeading().toUpperCase(Locale.ROOT);

            if (u.startsWith("CONFIGURATION SECTION")) {
                inConfiguration = true;
                out.add(line);
                continue;
            }

            if (inConfiguration && (
                    u.startsWith("DATA DIVISION")
                            || u.startsWith("INPUT-OUTPUT SECTION")
                            || u.startsWith("PROCEDURE DIVISION")
                            || u.startsWith("SPECIAL-NAMES")
                            || u.startsWith("SOURCE-COMPUTER")
                            || u.startsWith("OBJECT-COMPUTER")
            )) {
                inConfiguration = false;
            }

            if (inConfiguration && u.matches("^FUNCTION\\b.*")) {
                continue;
            }

            out.add(line);
        }

        return String.join("\n", out);
    }

    private String insertMissingProgramId(String s) {
        if (s == null || s.isBlank()) return s;

        String uAll = s.toUpperCase(Locale.ROOT);
        if (!uAll.matches("(?s).*\\bIDENTIFICATION\\s+DIVISION\\b.*")) return s;
        if (uAll.matches("(?s).*\\bPROGRAM-ID\\b.*")) return s;

        String[] lines = s.split("\\R", -1);
        List<String> out = new ArrayList<>();

        boolean afterIdentification = false;
        boolean inserted = false;

        for (String line : lines) {
            String x = line.stripLeading().toUpperCase(Locale.ROOT);

            if (!inserted && x.matches("^IDENTIFICATION\\s+DIVISION\\s*\\.?\\s*$")) {
                out.add(line);
                afterIdentification = true;
                continue;
            }

            if (afterIdentification && !inserted) {
                if (x.isBlank()) {
                    out.add(line);
                    continue;
                }

                if (x.matches("^(ENVIRONMENT|DATA|PROCEDURE)\\s+DIVISION\\b.*")) {
                    out.add("       PROGRAM-ID. DUMMY.");
                    inserted = true;
                }
            }

            out.add(line);
        }

        return String.join("\n", out);
    }

    private String ensureFinalPeriod(String s) {
        if (s == null || s.isBlank()) {
            return s;
        }

        String trimmed = s.stripTrailing();

        // Already properly terminated.
        if (trimmed.endsWith(".")) {
            return trimmed;
        }

        // Add final period so the parser can close the last statement/paragraph.
        return trimmed + ".";
    }

    private String replaceUnsupportedStringUnstringBlocks(String s) {
        if (s == null || s.isBlank()) return s;

        String[] lines = s.split("\\R", -1);
        List<String> out = new ArrayList<>();

        boolean inBlock = false;
        String blockType = null;

        for (String line : lines) {
            String x = line.stripLeading().toUpperCase(Locale.ROOT);

            if (!inBlock && (x.startsWith("STRING ") || x.startsWith("UNSTRING "))) {
                inBlock = true;
                blockType = x.startsWith("STRING ") ? "STRING" : "UNSTRING";

                // Replace unsupported formatting/parsing statement with parser-safe stub.
                out.add("       CONTINUE.");

                // Single-line STRING/UNSTRING ending with period.
                if (x.endsWith(".")) {
                    inBlock = false;
                    blockType = null;
                }

                continue;
            }

            if (inBlock) {
                // End block at first period line.
                if (x.endsWith(".")) {
                    inBlock = false;
                    blockType = null;
                }
                continue;
            }

            out.add(line);
        }

        return String.join("\n", out);
    }

    /**
     * Merge a fixed-format continuation line (indicator '-' in column 7)
     * into the immediately preceding logical statement.
     *
     * For a continued literal, COBOL may repeat the opening quotation mark
     * on the continuation line. In that case the repeated delimiter is
     * removed and no artificial whitespace is inserted into the literal.
     */
    private void appendFixedFormatContinuation(List<String> processed, String codeArea) {
        String continuation = codeArea == null ? "" : codeArea.stripLeading();

        if (continuation.isBlank()) {
            return;
        }

        if (processed.isEmpty()) {
            // Malformed/orphan continuation: preserve the text rather than
            // silently dropping it. The parser may then reject the source.
            processed.add(continuation);
            return;
        }

        int previousIndex = processed.size() - 1;
        String previous = processed.get(previousIndex);

        processed.set(
                previousIndex,
                mergeFixedFormatContinuation(previous, continuation)
        );
    }

    private String mergeFixedFormatContinuation(String previous, String continuation) {
        if (previous == null || previous.isBlank()) {
            return continuation;
        }

        if (hasOpenLiteral(previous, '"')) {
            return previous + stripRepeatedLiteralDelimiter(continuation, '"');
        }

        if (hasOpenLiteral(previous, '\'')) {
            return previous + stripRepeatedLiteralDelimiter(continuation, '\'');
        }

        return previous + " " + continuation;
    }

    private String stripRepeatedLiteralDelimiter(String continuation, char delimiter) {
        if (!continuation.isEmpty() && continuation.charAt(0) == delimiter) {
            return continuation.substring(1);
        }
        return continuation;
    }

    /**
     * Determine whether a COBOL literal delimiter is still open at line end.
     * Escaped quotes written as doubled delimiters are skipped.
     */
    private boolean hasOpenLiteral(String text, char delimiter) {
        boolean open = false;

        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) != delimiter) {
                continue;
            }

            if (i + 1 < text.length() && text.charAt(i + 1) == delimiter) {
                i++;
                continue;
            }

            open = !open;
        }

        return open;
    }
}
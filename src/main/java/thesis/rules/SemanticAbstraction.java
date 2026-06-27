package thesis.rules;

import java.util.*;



/**
 * Semantic abstraction for COBOL normalized text:
 * - Replace identifiers with VAR1/VAR2/... within a single string (stable mapping inside the string)
 * - Keep COBOL keywords and operators
 * - Keep placeholders like <N>, <S>, <ZERO>, <SPACE>
 *
 * Design decision: abstraction stays at structural/identifier level.
 * Semantic type inference (e.g. NUMERIC_VALUE < THRESHOLD) is intentionally
 * delegated to the LLM annotation step, which has sufficient context to
 * distinguish numeric counters from thresholds, status codes from amounts, etc.
 * A rule-based type mapping would require DATA DIVISION type information
 * that is not available at this pipeline stage.
 */

public final class SemanticAbstraction {

    public record AbstractRuleParts(
            String predicate,
            String thenText,
            String elseText
    ) {}

    private SemanticAbstraction() {}

    // Keywords/verbs you do NOT want to replace
    private static final Set<String> RESERVED = Set.of(
            // boolean / evaluate
            "TRUE", "FALSE", "OTHER", "ALSO",

            // logical
            "AND", "OR", "NOT",

            // actions
            "MOVE", "PERFORM", "CALL", "ADD", "SUBTRACT", "MULTIPLY", "DIVIDE",
            "COMPUTE", "DISPLAY", "SET", "INITIALIZE", "STRING", "UNSTRING",
            "READ", "WRITE", "REWRITE", "DELETE", "OPEN", "CLOSE", "START",
            "ACCEPT", "INSPECT",
            "EXEC", "NEXT", "SENTENCE", "CONTINUE", "STOP", "RUN",
            "GOBACK", "EXIT", "GO", "TO",

            // control tokens
            "IF", "THEN", "ELSE", "END-IF",
            "EVALUATE", "WHEN", "END-EVALUATE",

            // terminated scopes / exception phrases
            "END-COMPUTE", "END-CALL",
            "ON", "EXCEPTION",

            // COBOL syntax
            "THRU", "THROUGH", "USING", "INTO", "BY", "SIZE", "DELIMITED",
            "LENGTH", "OF", "FUNCTION",
            "REPLACING", "ALL",

            // intrinsic functions used in the corpus
            "UPPER-CASE",

            // predicates / figurative constants
            "IS", "EQUAL", "GREATER", "LESS", "THAN",
            "NUMERIC", "ALPHABETIC", "POSITIVE", "NEGATIVE",
            "ZERO", "ZEROS", "SPACE", "SPACES",
            "HIGH-VALUE", "HIGH-VALUES", "LOW-VALUE", "LOW-VALUES"
    );

    // Placeholder tokens already generalized by the normalizer
    private static final Set<String> PLACEHOLDERS = Set.of(
            "<N>", "<S>", "<ZERO>", "<SPACE>"
    );


    public static AbstractRuleParts abstractIf(
            String conditionNorm,
            String thenNorm,
            String elseNorm) {

        Map<String, String> sharedMap = new LinkedHashMap<>();
        int[] counter = {1};

        String absPredicate = abstractWithMap(conditionNorm, sharedMap, counter);
        String absThen = abstractWithMap(thenNorm, sharedMap, counter);
        String absElse = abstractWithMap(elseNorm, sharedMap, counter);

        return new AbstractRuleParts(absPredicate, absThen, absElse);
    }

    /**
     * Abstract a normalized predicate/action string.
     *
     * Input example:  "CUSTOMER-STATUS = <S>"
     * Output example: "VAR1 = <S>"
     *
     * COBOL identifiers (including hyphenated names like CUSTOMER-STATUS)
     * are treated as atomic tokens and mapped to VAR1, VAR2, ... in order
     * of first appearance. The mapping is stable within a single string
     * but intentionally NOT stable across strings — cross-string grouping
     * is the purpose of the abstract canonical key (SHA-256 hash of the
     * abstracted components), not of the human-readable abstracted text.
     */
    public static String abstractText(String s) {
        if (s == null) return null;

        String input = s.trim();
        if (input.isEmpty()) return null;

        // Tokenize: COBOL identifiers with hyphens are kept as single tokens
        List<String> tokens = lex(input);

        Map<String, String> map = new LinkedHashMap<>();
        int varCounter = 1;

        List<String> out = new ArrayList<>(tokens.size());
        for (String t : tokens) {
            String u = t.toUpperCase(Locale.ROOT);

            if (u.isBlank()) continue;

            // keep placeholders
            if (PLACEHOLDERS.contains(u)) {
                out.add(u);
                continue;
            }

            // keep numbers that slipped through (rare)
            if (u.matches("[+-]?\\d+(?:\\.\\d+)?")) {
                out.add("<N>");
                continue;
            }

            // keep string literal that slipped through (rare)
            if (u.startsWith("'") && u.endsWith("'") && u.length() >= 2) {
                out.add("<S>");
                continue;
            }

            // keep operators/punct
            if (isPunctOrOp(u)) {
                out.add(u);
                continue;
            }

            // keep reserved keywords (checked AFTER placeholder/literal checks)
            if (RESERVED.contains(u)) {
                out.add(u);
                continue;
            }

            // COBOL identifier: [A-Z][A-Z0-9-]* (hyphens are part of the name)
            // Normalizer output is already uppercase, but we stay robust.
            if (u.matches("[A-Z][A-Z0-9-]*")) {
                String rep = map.get(u);
                if (rep == null) {
                    rep = "VAR" + varCounter++;
                    map.put(u, rep);
                }
                out.add(rep);
                continue;
            }

            // default keep
            out.add(u);
        }

        String joined = joinPretty(out);
        return joined.isBlank() ? null : joined;
    }

    // ---------- helpers ----------

    private static boolean isPunctOrOp(String u) {
        return switch (u) {
            case "=", "<>", ">", "<", ">=", "<=",
                 "+", "*", "/", "(", ")", ",", ".", ":" -> true;
            default -> false;
        };
    }

    /**
     * Lexer that treats COBOL hyphenated identifiers as single tokens.
     *
     * Key difference from naive splitting: a hyphen between two word-characters
     * is kept as part of the identifier (e.g. CUSTOMER-STATUS stays together).
     * A standalone hyphen surrounded by spaces or adjacent to operators/digits
     * would be unusual in normalized COBOL predicates and is left as-is.
     *
     * The rule: flush the buffer and emit '-' as a standalone token ONLY when
     * the buffer is empty (hyphen at start of token) or when the next char is
     * a digit or operator. Otherwise, append the hyphen to the current buffer.
     */
    private static List<String> lex(String s) {
        List<String> out = new ArrayList<>();
        StringBuilder buf = new StringBuilder();

        // Operators and punctuation that are always standalone
        // Note: '-' is intentionally NOT in this set — handled inline below
        Set<Character> singles = Set.of('(', ')', ',', '.', ':', '+', '*', '/');

        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);

            // whitespace => flush
            if (Character.isWhitespace(c)) {
                flush(buf, out);
                continue;
            }

            // 2-char operators: >=, <=, <>
            if ((c == '>' || c == '<') && i + 1 < s.length()) {
                char n = s.charAt(i + 1);
                if (n == '=' || (c == '<' && n == '>')) {
                    flush(buf, out);
                    out.add("" + c + n);
                    i++;
                    continue;
                }
            }

            // single-char operator '='
            if (c == '=') {
                flush(buf, out);
                out.add("=");
                continue;
            }

            // hyphen: part of COBOL identifier if buffer is non-empty and
            // the next character is a letter (e.g. CUSTOMER-STATUS)
            if (c == '-') {
                if (!buf.isEmpty()
                        && i + 1 < s.length()
                        && Character.isLetterOrDigit(s.charAt(i + 1))) {
                    // Interior hyphen in a COBOL identifier:
                    // CUSTOMER-STATUS, INPUT-2, WS-FIELD-01
                    buf.append(c);
                } else {
                    // Standalone subtraction / minus operator
                    flush(buf, out);
                    out.add("-");
                }
                continue;
            }

            // other single-char punctuation / operators
            if (singles.contains(c)) {
                flush(buf, out);
                out.add(String.valueOf(c));
                continue;
            }

            // normal char (letter, digit, underscore, etc.)
            buf.append(c);
        }

        flush(buf, out);
        return out;
    }

    private static void flush(StringBuilder buf, List<String> out) {
        if (!buf.isEmpty()) {
            out.add(buf.toString());
            buf.setLength(0);
        }
    }

    private static String joinPretty(List<String> tokens) {
        StringBuilder sb = new StringBuilder();

        for (int i = 0; i < tokens.size(); i++) {
            String t = tokens.get(i);

            if (sb.isEmpty()) {
                sb.append(t);
                continue;
            }

            String prev = tokens.get(i - 1);

            // No space before ')', ',', '.', ':'
            if (")".equals(t) || ",".equals(t) || ".".equals(t) || ":".equals(t)) {
                sb.append(t);
                continue;
            }

            // No space after '('
            if ("(".equals(prev)) {
                sb.append(t);
                continue;
            }

            // Default: space
            sb.append(' ').append(t);
        }

        return sb.toString().trim();
    }

    /**
     * Abstract subject_norm and when_norm with a SHARED variable map.
     * This ensures that if BEISPIEL appears in subject and again in when
     * (e.g. "BEISPIEL = <S>"), it gets the same VAR1 assignment.
     *
     * Result: "VAR1 | WHEN | VAR1 = <S>"
     * instead of: "VAR1 | WHEN | VAR2 = <S>"  (wrong — separate maps)
     */
    public static AbstractRuleParts abstractEvaluateRule(
            String subjectNorm,
            String whenNorm,
            String thenNorm,
            String elseNorm) {

        Map<String, String> sharedMap = new LinkedHashMap<>();
        int[] counter = {1};

        String absSubject = abstractWithMap(subjectNorm, sharedMap, counter);
        String absWhen = abstractWithMap(whenNorm, sharedMap, counter);

        String subject = absSubject == null ? "" : absSubject;
        String when = absWhen == null ? "" : absWhen;

        String absPredicate = subject + " | WHEN | " + when;
        String absThen = abstractWithMap(thenNorm, sharedMap, counter);
        String absElse = abstractWithMap(elseNorm, sharedMap, counter);

        return new AbstractRuleParts(absPredicate, absThen, absElse);
    }

    /**
     * Internal: abstract a single string using a provided shared map.
     */
    private static String abstractWithMap(
            String s,
            Map<String, String> map,
            int[] counter) {

        if (s == null) return null;
        String input = s.trim();
        if (input.isEmpty()) return null;

        List<String> tokens = lex(input);
        List<String> out = new ArrayList<>(tokens.size());

        for (String t : tokens) {
            String u = t.toUpperCase(Locale.ROOT);
            if (u.isBlank()) continue;

            if (PLACEHOLDERS.contains(u)) { out.add(u); continue; }
            if (u.matches("[+-]?\\d+(?:\\.\\d+)?")) { out.add("<N>"); continue; }
            if (u.startsWith("'") && u.endsWith("'") && u.length() >= 2) {
                out.add("<S>"); continue;
            }
            if (isPunctOrOp(u)) { out.add(u); continue; }
            if (RESERVED.contains(u)) { out.add(u); continue; }

            if (u.matches("[A-Z][A-Z0-9-]*")) {
                String rep = map.computeIfAbsent(u, k -> "VAR" + counter[0]++);
                out.add(rep);
                continue;
            }
            out.add(u);
        }

        String joined = joinPretty(out);
        return joined.isBlank() ? null : joined;
    }
}
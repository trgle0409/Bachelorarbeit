package thesis.rules;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

public final class CanonicalKey {
    private CanonicalKey() {}

    public static String of(RuleKind kind,
                            String conditionNorm,
                            String thenNorm,
                            String elseNorm,
                            boolean hasElse) {

        String payload = String.join("|",
                kind == null ? "" : kind.name(),
                hasElse ? "1" : "0",
                safe(conditionNorm),
                safe(thenNorm),
                safe(elseNorm)
        );

        return sha256Hex(payload);
    }

    private static String safe(String s) {
        if (s == null) return "";
        // normalize whitespace to stabilize key
        return s.trim().replaceAll("\\s+", " ");
    }

    private static String sha256Hex(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] dig = md.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(dig.length * 2);
            for (byte b : dig) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("Cannot compute sha256", e);
        }
    }
}
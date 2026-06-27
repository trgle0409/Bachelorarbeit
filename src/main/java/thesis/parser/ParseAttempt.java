package thesis.parser;

import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.tree.ParseTree;

public record ParseAttempt(
        ParseStatus status,
        ParseMode mode,
        String message,
        Integer line,
        Integer charPos,
        String token,
        String snippet,
        ParseTree tree,
        CommonTokenStream tokens
) {}
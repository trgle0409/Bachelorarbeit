package thesis.parser;

import org.antlr.v4.runtime.*;

import java.util.ArrayList;
import java.util.List;

public class CollectingErrorListener extends BaseErrorListener {

    public record Err(int line, int charPos, String offending, String msg) {}

    private final List<Err> errors = new ArrayList<>();

    @Override
    public void syntaxError(Recognizer<?, ?> recognizer,
                            Object offendingSymbol,
                            int line,
                            int charPositionInLine,
                            String msg,
                            RecognitionException e) {
        String off = (offendingSymbol instanceof Token t) ? t.getText() : String.valueOf(offendingSymbol);
        errors.add(new Err(line, charPositionInLine, off, msg));
    }

    public boolean hasErrors() { return !errors.isEmpty(); }
    public Err first() { return errors.isEmpty() ? null : errors.get(0); }
    public List<Err> all() { return errors; }
}

package thesis.parser;

public enum ParseStatus {
    VALID,
    IO_ERROR,
    LEXER_ERROR,
    PARSER_ERROR,
    EMPTY_AFTER_CLEAN,
    SKIPPED_COPYBOOK,
    SKIPPED_FUNCTION_UNIT
}

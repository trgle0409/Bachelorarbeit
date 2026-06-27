package thesis.ai;

public class OpenAiTransientException extends RuntimeException {
    public OpenAiTransientException(String message, Throwable cause) {
        super(message, cause);
    }
}
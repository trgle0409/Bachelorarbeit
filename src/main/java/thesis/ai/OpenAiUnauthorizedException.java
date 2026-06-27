package thesis.ai;

public class OpenAiUnauthorizedException extends RuntimeException {
    public OpenAiUnauthorizedException(String message, Throwable cause) {
        super(message, cause);
    }
}
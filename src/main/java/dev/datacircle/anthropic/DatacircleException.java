package dev.datacircle.anthropic;

/**
 * Raised when a Datacircle API call fails: its message says why, in words the model can act on. The SDK's tool runner
 * sends it back to the model as an error result.
 */
public final class DatacircleException extends RuntimeException {

    /** HTTP status, or -1 when the request received no HTTP response. */
    private final int statusCode;

    DatacircleException(String message) {
        this(-1, message, null);
    }

    DatacircleException(String message, Throwable cause) {
        this(-1, message, cause);
    }

    DatacircleException(int statusCode, String message, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
    }

    /**
     * The API's HTTP status.
     *
     * @return the status, or -1 when no answer came
     */
    public int statusCode() {
        return statusCode;
    }

    /** The same failure, its message followed by what the model can do next. */
    DatacircleException withHint(String hint) {
        return new DatacircleException(statusCode, getMessage() + " " + hint, getCause());
    }
}

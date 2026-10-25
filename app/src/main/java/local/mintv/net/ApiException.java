package local.mintv.net;

/** Failure type shared by the login API and the channel repository. */
public final class ApiException extends Exception {

    public static final int NOT_IMPLEMENTED = -1;
    public static final int NETWORK = -2;
    public static final int AUTH = -3;
    public static final int SERVER = -4;
    public static final int PARSE = -5;

    private final int code;
    private final int httpStatus;

    public ApiException(int code, String message) {
        this(code, message, 0, null);
    }

    public ApiException(int code, String message, int httpStatus, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.httpStatus = httpStatus;
    }

    public int code() {
        return code;
    }

    /** HTTP status when the failure came from the server, otherwise 0. */
    public int httpStatus() {
        return httpStatus;
    }
}

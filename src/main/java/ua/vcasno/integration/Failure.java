package ua.vcasno.integration;

public class Failure extends RuntimeException {
    public final int status;
    public final String code;
    public Failure(int status, String code, String message) {
        super(message); this.status = status; this.code = code;
    }
    public static Failure invalid(String message) { return new Failure(422, "INVALID_LEAD_DATA", message); }
    public static Failure conflict(String message) { return new Failure(409, "RECEIPT_CONFLICT", message); }
}

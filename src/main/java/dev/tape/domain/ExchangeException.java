package dev.tape.domain;

public final class ExchangeException extends RuntimeException {
    public enum Code { INVALID_INPUT, NOT_FOUND, CONFLICT, UNAVAILABLE }
    private final Code code;
    public ExchangeException(Code code, String message) { super(message); this.code = code; }
    public Code code() { return code; }
}

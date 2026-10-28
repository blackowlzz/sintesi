package dev.sintesi.protocol;

public final class DecodeException extends RuntimeException {

    public DecodeException(String message) {
        super(message, null, false, false);
    }
}

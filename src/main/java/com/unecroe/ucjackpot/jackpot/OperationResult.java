package com.unecroe.ucjackpot.jackpot;

public record OperationResult(boolean success, String messageKey, double value, long seconds) {
    public static OperationResult ok(String messageKey, double value) {
        return new OperationResult(true, messageKey, value, 0);
    }

    public static OperationResult fail(String messageKey) {
        return new OperationResult(false, messageKey, 0, 0);
    }

    public static OperationResult fail(String messageKey, double value) {
        return new OperationResult(false, messageKey, value, 0);
    }

    public static OperationResult wait(String messageKey, long seconds) {
        return new OperationResult(false, messageKey, 0, seconds);
    }
}



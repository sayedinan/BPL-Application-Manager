package com.bpl.orderapp.admin.common;

public class IdempotencyConflictException extends RuntimeException {
    public IdempotencyConflictException() { super("Duplicate idempotency key"); }
}
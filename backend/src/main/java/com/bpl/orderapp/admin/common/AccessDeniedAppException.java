package com.bpl.orderapp.admin.common;

public class AccessDeniedAppException extends RuntimeException {
    public AccessDeniedAppException() { super("Not authorized for this application"); }
}

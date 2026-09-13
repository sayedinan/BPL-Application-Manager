package com.bpl.orderapp.admin.common;

public class DuplicateNameException extends RuntimeException {
    public DuplicateNameException(String username) { super("Username already exists: " + username); }
}

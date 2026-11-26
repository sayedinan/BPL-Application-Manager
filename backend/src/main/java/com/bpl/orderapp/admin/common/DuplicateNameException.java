package com.bpl.orderapp.admin.common;

public class DuplicateNameException extends RuntimeException {
    public DuplicateNameException(String username) { this("Username already exists: ", username); }

    private DuplicateNameException(String prefix, String name) { super(prefix + name); }

    public static DuplicateNameException application(String name) {
        return new DuplicateNameException("An application with this name already exists: ", name);
    }
}

package com.bpl.orderapp.admin.common;

public class AdminCeilingException extends RuntimeException {
    public AdminCeilingException() { super("Admin cannot create Admin or Sys.Admin accounts"); }
}

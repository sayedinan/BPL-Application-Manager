package com.bpl.orderapp.admin.user.dto;

public record CreateUserResponse(Long id, String username, String role, String temporaryPassword) {}

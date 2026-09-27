package com.bpl.orderapp.admin.user.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import java.util.List;

public record CreateUserRequest(
    @NotBlank String username,
    @NotBlank String role,
    @NotBlank @Email String email,
    List<Long> assignedApplicationIds
) {}

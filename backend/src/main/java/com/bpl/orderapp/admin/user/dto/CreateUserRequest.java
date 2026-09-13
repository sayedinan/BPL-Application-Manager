package com.bpl.orderapp.admin.user.dto;

import jakarta.validation.constraints.NotBlank;
import java.util.List;

public record CreateUserRequest(
    @NotBlank String username,
    @NotBlank String role,
    List<Long> assignedApplicationIds
) {}

package com.bpl.orderapp.admin.user.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

public record CreateUserRequest(
    @NotBlank
    @Pattern(regexp = "[^\\p{Cc}\\p{Zl}\\p{Zp}]*", message = "username must not contain control characters or line breaks")
    String username,
    @NotBlank @Size(max = 150) String fullName,
    @NotBlank @Pattern(regexp = "SYS_ADMIN|ADMIN|USER", message = "role must be SYS_ADMIN, ADMIN or USER") String role,
    @NotBlank @Email String email,
    // Optional — unlike email, a user isn't required to have a phone
    // number (no SMS alerts is an acceptable state, per
    // NotificationRecipientResolver). Pattern matches the V15 CHECK
    // constraint exactly: "8801" + 9 digits, since the frontend
    // assembles this full value (locked "8801" prefix + typed
    // digits) before it ever reaches this DTO.
    @NotBlank
    @Pattern(regexp = "^8801[0-9]{9}$", message = "phoneNumber must be 8801 followed by 9 digits")
    String phoneNumber,
    List<Long> assignedApplicationIds
) {}

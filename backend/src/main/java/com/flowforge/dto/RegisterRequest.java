package com.flowforge.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @NotBlank @Email @Size(max = 254) String email,
        @NotNull @Size(min = 10) @MaxUtf8Bytes(72) String password,
        @NotBlank @Size(max = 100) String displayName) {
}

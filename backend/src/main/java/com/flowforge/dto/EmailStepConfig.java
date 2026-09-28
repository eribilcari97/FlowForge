package com.flowforge.dto;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

public record EmailStepConfig(
        @NotEmpty @Size(max = 20) List<@NotBlank String> to,
        @Size(max = 20) List<@NotBlank String> cc,
        @NotBlank @Size(max = 300) String subject,
        String text,
        String html) {
}

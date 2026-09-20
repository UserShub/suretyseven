package com.suretyseven.dto;

import jakarta.validation.constraints.NotBlank;

public record ObligeeDto(
        @NotBlank(message = "obligee.name is required")
        String name
) {}

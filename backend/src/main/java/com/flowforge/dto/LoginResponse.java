package com.flowforge.dto;

public record LoginResponse(String accessToken, long expiresIn, UserResponse user) {
}

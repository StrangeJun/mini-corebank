package com.corebank.auth.dto;

import com.corebank.user.UserResponse;

public record LoginResponse(String accessToken, UserResponse user) {
}

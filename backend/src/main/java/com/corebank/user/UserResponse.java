package com.corebank.user;

import com.corebank.domain.user.User;
import com.corebank.domain.user.UserRole;

public record UserResponse(Long id, String email, String name, UserRole role) {

    public static UserResponse from(User user) {
        return new UserResponse(user.getId(), user.getEmail(), user.getName(), user.getRole());
    }
}

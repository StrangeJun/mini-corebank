package com.corebank.auth.jwt;

import com.corebank.domain.user.UserRole;

/** SecurityContext 에 저장되는 인증 주체. 비밀번호 등 민감정보는 담지 않는다. */
public record AuthUser(Long userId, String email, UserRole role) {
}

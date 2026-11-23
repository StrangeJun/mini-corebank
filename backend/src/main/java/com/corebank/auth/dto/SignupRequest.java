package com.corebank.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** BCrypt 입력 한계(72바이트)를 넘지 않도록 비밀번호 최대 길이를 제한한다. */
public record SignupRequest(
        @NotBlank @Email @Size(max = 255) String email,
        @NotBlank @Size(min = 8, max = 64) String password,
        @NotBlank @Size(max = 100) String name) {

    @Override
    public String toString() {
        return "SignupRequest[email=" + email + ", password=****, name=" + name + "]";
    }
}

package com.corebank.auth;

import com.corebank.audit.AuditEvent;
import com.corebank.audit.AuditLogService;
import com.corebank.auth.dto.LoginRequest;
import com.corebank.auth.dto.LoginResponse;
import com.corebank.auth.dto.SignupRequest;
import com.corebank.auth.jwt.AuthUser;
import com.corebank.auth.jwt.JwtTokenProvider;
import com.corebank.common.exception.BusinessException;
import com.corebank.common.exception.ErrorCode;
import com.corebank.common.util.MaskingUtil;
import com.corebank.domain.audit.AuditResult;
import com.corebank.domain.user.User;
import com.corebank.domain.user.UserRepository;
import com.corebank.domain.user.UserRole;
import com.corebank.user.UserResponse;
import java.util.Locale;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    static final String ACTION_SIGNUP = "USER_SIGNUP";
    static final String ACTION_LOGIN_SUCCESS = "LOGIN_SUCCESS";
    static final String ACTION_LOGIN_FAIL = "LOGIN_FAIL";
    private static final String TARGET_USER = "USER";

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider tokenProvider;
    private final AuditLogService auditLogService;
    /** 없는 이메일로 로그인해도 BCrypt 비교를 수행해 응답 시간으로 계정 존재 여부가 드러나지 않게 한다. */
    private final String dummyPasswordHash;

    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder,
                       JwtTokenProvider tokenProvider, AuditLogService auditLogService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.tokenProvider = tokenProvider;
        this.auditLogService = auditLogService;
        this.dummyPasswordHash = passwordEncoder.encode("dummy-password-for-timing");
    }

    @Transactional
    public UserResponse signup(SignupRequest request, String clientIp) {
        String email = normalizeEmail(request.email());
        if (userRepository.existsByEmail(email)) {
            throw new BusinessException(ErrorCode.DUPLICATE_EMAIL);
        }
        User user;
        try {
            user = userRepository.saveAndFlush(new User(
                    email, passwordEncoder.encode(request.password()), request.name().trim(), UserRole.USER));
        } catch (DataIntegrityViolationException e) {
            // 동시 가입 경합: UNIQUE(email) 위반
            throw new BusinessException(ErrorCode.DUPLICATE_EMAIL);
        }
        auditLogService.logInTransaction(new AuditEvent(
                user.getId(), ACTION_SIGNUP, TARGET_USER, String.valueOf(user.getId()), AuditResult.SUCCESS,
                "signup email=" + MaskingUtil.maskEmail(email), clientIp));
        return UserResponse.from(user);
    }

    public LoginResponse login(LoginRequest request, String clientIp) {
        String email = normalizeEmail(request.email());
        Optional<User> found = userRepository.findByEmail(email);
        String hash = found.map(User::getPasswordHash).orElse(dummyPasswordHash);
        boolean matches = passwordEncoder.matches(request.password(), hash);

        if (found.isEmpty() || !matches) {
            auditLogService.logIndependent(new AuditEvent(
                    found.map(User::getId).orElse(null), ACTION_LOGIN_FAIL, TARGET_USER,
                    found.map(u -> String.valueOf(u.getId())).orElse(null), AuditResult.FAILURE,
                    "login failed email=" + MaskingUtil.maskEmail(email), clientIp));
            // 이메일 없음/비밀번호 불일치를 구분하지 않는다.
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "이메일 또는 비밀번호가 올바르지 않습니다.");
        }

        User user = found.get();
        String token = tokenProvider.createAccessToken(new AuthUser(user.getId(), user.getEmail(), user.getRole()));
        auditLogService.logInTransaction(new AuditEvent(
                user.getId(), ACTION_LOGIN_SUCCESS, TARGET_USER, String.valueOf(user.getId()), AuditResult.SUCCESS,
                "login email=" + MaskingUtil.maskEmail(email), clientIp));
        return new LoginResponse(token, UserResponse.from(user));
    }

    @Transactional(readOnly = true)
    public UserResponse me(Long userId) {
        return userRepository.findById(userId)
                .map(UserResponse::from)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
    }

    private static String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}

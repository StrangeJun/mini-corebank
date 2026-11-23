package com.corebank.account;

import com.corebank.account.dto.AccountUnlockResponse;
import com.corebank.auth.jwt.AuthUser;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** /api/admin/** 는 SecurityConfig 에서 ADMIN 전용으로 막혀 있다. */
@RestController
@RequestMapping("/api/admin/accounts")
public class AdminAccountController {

    private final AccountService accountService;

    public AdminAccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    @PostMapping("/{accountId}/unlock")
    public AccountUnlockResponse unlock(@AuthenticationPrincipal AuthUser admin, @PathVariable Long accountId,
                                        HttpServletRequest http) {
        return accountService.unlock(admin.userId(), accountId, http.getRemoteAddr());
    }
}

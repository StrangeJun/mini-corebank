package com.corebank.account;

import com.corebank.account.dto.AccountResponse;
import com.corebank.account.dto.CreateAccountRequest;
import com.corebank.auth.jwt.AuthUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/accounts")
public class AccountController {

    private final AccountService accountService;

    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AccountResponse create(@AuthenticationPrincipal AuthUser user,
                                  @Valid @RequestBody CreateAccountRequest request, HttpServletRequest http) {
        return accountService.create(user.userId(), request, http.getRemoteAddr());
    }

    @GetMapping
    public List<AccountResponse> listMine(@AuthenticationPrincipal AuthUser user) {
        return accountService.listMine(user.userId());
    }

    @GetMapping("/{accountId}")
    public AccountResponse get(@AuthenticationPrincipal AuthUser user, @PathVariable Long accountId) {
        return accountService.get(user, accountId);
    }

    @PatchMapping("/{accountId}/close")
    public AccountResponse close(@AuthenticationPrincipal AuthUser user, @PathVariable Long accountId,
                                 HttpServletRequest http) {
        return accountService.close(user.userId(), accountId, http.getRemoteAddr());
    }
}

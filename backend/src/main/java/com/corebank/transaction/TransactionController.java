package com.corebank.transaction;

import com.corebank.auth.jwt.AuthUser;
import com.corebank.transaction.dto.DepositRequest;
import com.corebank.transaction.dto.TransactionResponse;
import com.corebank.transaction.dto.TransferRequest;
import com.corebank.transaction.dto.WithdrawRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/transactions")
public class TransactionController {

    /** 없거나 공백이면 IdempotencyService 가 INVALID_INPUT 으로 거절한다. */
    private static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    private final TransactionService transactionService;

    public TransactionController(TransactionService transactionService) {
        this.transactionService = transactionService;
    }

    @PostMapping("/deposit")
    public TransactionResponse deposit(@AuthenticationPrincipal AuthUser user,
                                       @RequestHeader(value = IDEMPOTENCY_KEY, required = false) String key,
                                       @Valid @RequestBody DepositRequest request, HttpServletRequest http) {
        return transactionService.deposit(user.userId(), key, request, http.getRemoteAddr());
    }

    @PostMapping("/withdraw")
    public TransactionResponse withdraw(@AuthenticationPrincipal AuthUser user,
                                        @RequestHeader(value = IDEMPOTENCY_KEY, required = false) String key,
                                        @Valid @RequestBody WithdrawRequest request, HttpServletRequest http) {
        return transactionService.withdraw(user.userId(), key, request, http.getRemoteAddr());
    }

    @PostMapping("/transfer")
    public TransactionResponse transfer(@AuthenticationPrincipal AuthUser user,
                                        @RequestHeader(value = IDEMPOTENCY_KEY, required = false) String key,
                                        @Valid @RequestBody TransferRequest request, HttpServletRequest http) {
        return transactionService.transfer(user.userId(), key, request, http.getRemoteAddr());
    }
}

package com.corebank.transaction;

import com.corebank.auth.jwt.AuthUser;
import com.corebank.common.PageResponse;
import com.corebank.domain.ledger.EntryType;
import com.corebank.domain.transaction.TransactionType;
import com.corebank.transaction.dto.TransactionHistoryItem;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class TransactionHistoryController {

    private final TransactionHistoryService historyService;

    public TransactionHistoryController(TransactionHistoryService historyService) {
        this.historyService = historyService;
    }

    @GetMapping("/api/accounts/{accountId}/transactions")
    public PageResponse<TransactionHistoryItem> history(
            @AuthenticationPrincipal AuthUser user,
            @PathVariable Long accountId,
            @RequestParam(required = false) TransactionType type,
            @RequestParam(required = false) EntryType entryType,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return historyService.find(user, accountId,
                new TransactionHistoryService.Filter(type, entryType, fromDate, toDate), page, size);
    }
}

package com.corebank.scheduled;

import com.corebank.auth.jwt.AuthUser;
import com.corebank.common.PageResponse;
import com.corebank.domain.scheduled.ScheduledTransferStatus;
import com.corebank.scheduled.dto.CreateScheduledTransferRequest;
import com.corebank.scheduled.dto.ScheduledTransferResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/scheduled-transfers")
public class ScheduledTransferController {

    /** 없거나 공백이면 IdempotencyService 가 INVALID_INPUT 으로 거절한다. */
    private static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    private final ScheduledTransferService scheduledTransferService;

    public ScheduledTransferController(ScheduledTransferService scheduledTransferService) {
        this.scheduledTransferService = scheduledTransferService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ScheduledTransferResponse create(@AuthenticationPrincipal AuthUser user,
                                            @RequestHeader(value = IDEMPOTENCY_KEY, required = false) String key,
                                            @Valid @RequestBody CreateScheduledTransferRequest request,
                                            HttpServletRequest http) {
        return scheduledTransferService.create(user.userId(), key, request, http.getRemoteAddr());
    }

    @GetMapping
    public PageResponse<ScheduledTransferResponse> list(@AuthenticationPrincipal AuthUser user,
                                                        @RequestParam(required = false) ScheduledTransferStatus status,
                                                        @RequestParam(defaultValue = "0") int page,
                                                        @RequestParam(defaultValue = "20") int size) {
        return scheduledTransferService.listMine(user.userId(), status, page, size);
    }

    @PostMapping("/{id}/cancel")
    public ScheduledTransferResponse cancel(@AuthenticationPrincipal AuthUser user, @PathVariable Long id,
                                            HttpServletRequest http) {
        return scheduledTransferService.cancel(user.userId(), id, http.getRemoteAddr());
    }
}

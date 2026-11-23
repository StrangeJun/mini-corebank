package com.corebank.transaction;

import com.corebank.account.AccountService;
import com.corebank.auth.jwt.AuthUser;
import com.corebank.common.PageParams;
import com.corebank.common.PageResponse;
import com.corebank.common.exception.BusinessException;
import com.corebank.common.exception.ErrorCode;
import com.corebank.common.util.MaskingUtil;
import com.corebank.domain.account.Account;
import com.corebank.domain.account.AccountRepository;
import com.corebank.domain.ledger.EntryType;
import com.corebank.domain.ledger.LedgerEntry;
import com.corebank.domain.transaction.Transaction;
import com.corebank.domain.transaction.TransactionType;
import com.corebank.transaction.dto.TransactionHistoryItem;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class TransactionHistoryService {

    private final AccountService accountService;
    private final AccountRepository accountRepository;
    private final EntityManager em;

    public TransactionHistoryService(AccountService accountService, AccountRepository accountRepository,
                                     EntityManager em) {
        this.accountService = accountService;
        this.accountRepository = accountRepository;
        this.em = em;
    }

    public record Filter(TransactionType type, EntryType entryType, LocalDate fromDate, LocalDate toDate) {
    }

    public PageResponse<TransactionHistoryItem> find(AuthUser user, Long accountId, Filter filter, int page, int size) {
        PageParams.validate(page, size);
        if (filter.fromDate() != null && filter.toDate() != null && filter.fromDate().isAfter(filter.toDate())) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "fromDate 는 toDate 보다 늦을 수 없습니다.");
        }
        Account account = accountService.findReadable(user, accountId);

        // null 파라미터 비교(:p is null)는 PostgreSQL 에서 타입 추론 오류가 날 수 있어 조건을 동적으로 붙인다.
        StringBuilder where = new StringBuilder(" from LedgerEntry e, Transaction t"
                + " where t.id = e.transactionId and e.accountId = :accountId");
        Map<String, Object> params = new HashMap<>();
        params.put("accountId", account.getId());
        if (filter.type() != null) {
            where.append(" and t.txType = :type");
            params.put("type", filter.type());
        }
        if (filter.entryType() != null) {
            where.append(" and e.entryType = :entryType");
            params.put("entryType", filter.entryType());
        }
        if (filter.fromDate() != null) {
            where.append(" and e.createdAt >= :from");
            params.put("from", filter.fromDate().atStartOfDay());
        }
        if (filter.toDate() != null) {
            where.append(" and e.createdAt < :to");
            params.put("to", filter.toDate().plusDays(1).atStartOfDay());
        }

        TypedQuery<Long> countQuery = em.createQuery("select count(e)" + where, Long.class);
        TypedQuery<Object[]> rowQuery = em.createQuery(
                "select e, t" + where + " order by e.createdAt desc, e.id desc", Object[].class);
        params.forEach((k, v) -> {
            countQuery.setParameter(k, v);
            rowQuery.setParameter(k, v);
        });
        long total = countQuery.getSingleResult();
        List<Object[]> rows = rowQuery.setFirstResult(page * size).setMaxResults(size).getResultList();

        Map<Long, String> counterpartyNumbers = loadCounterparties(account.getId(), rows);
        List<TransactionHistoryItem> items = rows.stream().map(row -> {
            LedgerEntry e = (LedgerEntry) row[0];
            Transaction t = (Transaction) row[1];
            String counterparty = counterpartyNumbers.get(counterpartyId(account.getId(), t));
            return new TransactionHistoryItem(t.getId(), t.getTxType(), e.getEntryType(), e.getAmount(),
                    e.getBalanceAfter(), MaskingUtil.maskAccountNumber(counterparty), t.getDescription(),
                    e.getCreatedAt());
        }).toList();
        return PageResponse.of(items, page, size, total);
    }

    private Map<Long, String> loadCounterparties(Long accountId, List<Object[]> rows) {
        List<Long> ids = rows.stream()
                .map(row -> counterpartyId(accountId, (Transaction) row[1]))
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        return accountRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(Account::getId, Account::getAccountNumber, (a, b) -> a));
    }

    private static Long counterpartyId(Long accountId, Transaction t) {
        return accountId.equals(t.getFromAccountId()) ? t.getToAccountId() : t.getFromAccountId();
    }
}

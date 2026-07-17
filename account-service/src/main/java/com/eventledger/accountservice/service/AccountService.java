package com.eventledger.accountservice.service;

import com.eventledger.accountservice.domain.Account;
import com.eventledger.accountservice.domain.AccountTransaction;
import com.eventledger.accountservice.dto.AccountResponse;
import com.eventledger.accountservice.dto.BalanceResponse;
import com.eventledger.accountservice.dto.TransactionRequest;
import com.eventledger.accountservice.dto.TransactionResponse;
import com.eventledger.accountservice.exception.AccountNotFoundException;
import com.eventledger.accountservice.exception.EventIdConflictException;
import com.eventledger.accountservice.repository.AccountRepository;
import com.eventledger.accountservice.repository.AccountTransactionRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Service
public class AccountService {

    private static final Logger log = LoggerFactory.getLogger(AccountService.class);

    private final AccountRepository accountRepository;
    private final AccountTransactionRepository transactionRepository;
    private final TransactionApplier transactionApplier;
    private final MeterRegistry meterRegistry;

    public AccountService(AccountRepository accountRepository,
                           AccountTransactionRepository transactionRepository,
                           TransactionApplier transactionApplier,
                           MeterRegistry meterRegistry) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.transactionApplier = transactionApplier;
        this.meterRegistry = meterRegistry;
    }

    public TransactionResponse applyTransaction(String accountId, TransactionRequest request) {
        Optional<AccountTransaction> existing = transactionRepository.findByEventId(request.eventId());
        if (existing.isPresent()) {
            log.info("duplicate transaction eventId={} accountId={}", request.eventId(), accountId);
            return duplicateResponse(accountId, existing.get());
        }

        try {
            ApplyResult result = transactionApplier.apply(accountId, request);
            meterRegistry.counter("account.transactions.applied", "type", request.type().name()).increment();
            return TransactionResponse.from(result.transaction(), result.account().getBalance(), false);
        } catch (DataIntegrityViolationException ex) {
            AccountTransaction raced = transactionRepository.findByEventId(request.eventId())
                    .orElseThrow(() -> ex);
            log.info("concurrent duplicate transaction eventId={} accountId={}", request.eventId(), accountId);
            return duplicateResponse(accountId, raced);
        }
    }

    private TransactionResponse duplicateResponse(String accountId, AccountTransaction existing) {
        if (!existing.getAccountId().equals(accountId)) {
            throw new EventIdConflictException(existing.getEventId(), existing.getAccountId(), accountId);
        }
        meterRegistry.counter("account.transactions.duplicate").increment();
        BigDecimal balance = accountRepository.findById(accountId)
                .map(Account::getBalance)
                .orElse(BigDecimal.ZERO);
        return TransactionResponse.from(existing, balance, true);
    }

    @Transactional(readOnly = true)
    public BalanceResponse getBalance(String accountId) {
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new AccountNotFoundException(accountId));
        return new BalanceResponse(account.getAccountId(), account.getBalance(), account.getCurrency(), Instant.now());
    }

    @Transactional(readOnly = true)
    public AccountResponse getAccount(String accountId) {
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new AccountNotFoundException(accountId));
        List<TransactionResponse> recent = transactionRepository
                .findTop50ByAccountIdOrderByEventTimestampDesc(accountId).stream()
                .map(t -> TransactionResponse.from(t, account.getBalance(), false))
                .toList();
        return new AccountResponse(account.getAccountId(), account.getBalance(), account.getCurrency(), recent);
    }
}

package com.eventledger.accountservice.service;

import com.eventledger.accountservice.domain.Account;
import com.eventledger.accountservice.domain.AccountTransaction;
import com.eventledger.accountservice.dto.AccountResponse;
import com.eventledger.accountservice.dto.BalanceResponse;
import com.eventledger.accountservice.dto.TransactionRequest;
import com.eventledger.accountservice.dto.TransactionResponse;
import com.eventledger.accountservice.exception.AccountNotFoundException;
import com.eventledger.accountservice.exception.ConflictingDuplicateException;
import com.eventledger.accountservice.exception.CurrencyMismatchException;
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

    @Transactional
    public TransactionResponse applyTransaction(String accountId, TransactionRequest request) {
        Optional<AccountTransaction> existing = transactionRepository.findByEventId(request.eventId());
        if (existing.isPresent()) {
            log.info("duplicate transaction eventId={} accountId={}", request.eventId(), accountId);
            return duplicateResponse(accountId, request, existing.get());
        }

        // Pessimistic write lock on the account row: concurrent transactions against the same
        // accountId serialize on this lock for the rest of the transaction, so the balance read
        // below is never stale by the time it's written back - no lost updates, no retry loop
        // needed. A lock can only be taken on a row that exists, so a brand-new accountId is
        // created (if it isn't already there) in its own isolated transaction first - that keeps
        // a lost creation race between two concurrent first-ever transactions from poisoning this one.
        Optional<Account> existingAccount = transactionApplier.findAccountForUpdate(accountId);
        Account account;
        if (existingAccount.isPresent()) {
            account = existingAccount.get();
        } else {
            try {
                transactionApplier.createAccountIfAbsent(accountId, request.currency(), Instant.now());
            } catch (DataIntegrityViolationException raceLost) {
                // a concurrent request created this account first in its own isolated transaction;
                // that transaction rolled back cleanly on its own and never touched this one, so
                // it's safe to just re-read the row it created below
            }
            account = transactionApplier.findAccountForUpdate(accountId)
                    .orElseThrow(() -> new IllegalStateException("account " + accountId + " must exist after createAccountIfAbsent"));
        }

        if (!account.getCurrency().equals(request.currency())) {
            throw new CurrencyMismatchException(accountId, account.getCurrency(), request.currency());
        }

        try {
            ApplyResult result = transactionApplier.apply(account, accountId, request);
            meterRegistry.counter("account.transactions.applied", "type", request.type().name()).increment();
            log.info("transaction applied eventId={} accountId={} type={} balance={}",
                    request.eventId(), accountId, request.type(), result.account().getBalance());
            return TransactionResponse.from(result.transaction(), result.account().getBalance(), false);
        } catch (DataIntegrityViolationException ex) {
            AccountTransaction raced = transactionRepository.findByEventId(request.eventId())
                    .orElseThrow(() -> ex);
            log.info("concurrent duplicate transaction eventId={} accountId={}", request.eventId(), accountId);
            return duplicateResponse(accountId, request, raced);
        }
    }

    private TransactionResponse duplicateResponse(String accountId, TransactionRequest request, AccountTransaction existing) {
        if (!existing.getAccountId().equals(accountId)) {
            throw new EventIdConflictException(existing.getEventId(), existing.getAccountId(), accountId);
        }
        if (!isExactDuplicate(existing, request)) {
            throw new ConflictingDuplicateException(existing.getEventId());
        }
        meterRegistry.counter("account.transactions.duplicate").increment();
        BigDecimal balance = accountRepository.findById(accountId)
                .map(Account::getBalance)
                .orElse(BigDecimal.ZERO);
        return TransactionResponse.from(existing, balance, true);
    }

    private static boolean isExactDuplicate(AccountTransaction existing, TransactionRequest request) {
        return existing.getType() == request.type()
                && existing.getAmount().compareTo(request.amount()) == 0
                && existing.getCurrency().equals(request.currency())
                && existing.getEventTimestamp().equals(request.eventTimestamp());
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

package com.eventledger.accountservice.service;

import com.eventledger.accountservice.domain.Account;
import com.eventledger.accountservice.domain.AccountTransaction;
import com.eventledger.accountservice.domain.TransactionType;
import com.eventledger.accountservice.dto.TransactionRequest;
import com.eventledger.accountservice.repository.AccountRepository;
import com.eventledger.accountservice.repository.AccountTransactionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Runs the balance update and transaction insert as a single database transaction.
 * Kept as a separate bean so {@link Transactional} is honored via the Spring AOP proxy
 * even when called from {@link AccountService} (self-invocation would otherwise bypass it).
 */
@Service
class TransactionApplier {

    private final AccountRepository accountRepository;
    private final AccountTransactionRepository transactionRepository;

    TransactionApplier(AccountRepository accountRepository, AccountTransactionRepository transactionRepository) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
    }

    @Transactional
    public ApplyResult apply(String accountId, TransactionRequest request) {
        Instant now = Instant.now();

        Account account = accountRepository.findById(accountId)
                .orElseGet(() -> new Account(accountId, request.currency(), now));

        BigDecimal delta = request.type() == TransactionType.CREDIT
                ? request.amount()
                : request.amount().negate();
        account.applyDelta(delta, now);
        account = accountRepository.save(account);

        AccountTransaction transaction = new AccountTransaction(
                request.eventId(),
                accountId,
                request.type(),
                request.amount(),
                request.currency(),
                request.eventTimestamp(),
                now
        );
        transaction = transactionRepository.save(transaction);

        return new ApplyResult(account, transaction);
    }
}

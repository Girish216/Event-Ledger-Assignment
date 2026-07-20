package com.eventledger.accountservice.service;

import com.eventledger.accountservice.domain.Account;
import com.eventledger.accountservice.domain.AccountTransaction;
import com.eventledger.accountservice.domain.TransactionType;
import com.eventledger.accountservice.dto.TransactionRequest;
import com.eventledger.accountservice.repository.AccountRepository;
import com.eventledger.accountservice.repository.AccountTransactionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

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

    /**
     * Takes a pessimistic write lock on the account row for the duration of the caller's
     * transaction, so concurrent transactions against the same account serialize instead of
     * racing on a stale in-memory balance. Returns empty if the account doesn't exist yet.
     */
    @Transactional
    Optional<Account> findAccountForUpdate(String accountId) {
        return accountRepository.findWithLockByAccountId(accountId);
    }

    /**
     * Ensures an account row exists, creating it with a zero balance if it doesn't. Runs in its
     * own transaction: if two first-ever transactions for the same brand-new accountId race on
     * the insert, the loser simply discovers the winner's row already exists instead of failing -
     * and, being isolated, that race never poisons the caller's transaction. Deliberately doesn't
     * catch DataIntegrityViolationException here: once a flush fails, Hibernate marks this
     * transaction rollback-only regardless, so swallowing it here would still surface as
     * UnexpectedRollbackException at commit. Letting it propagate rolls back this isolated
     * transaction cleanly; the caller (in its own, unaffected transaction) catches and ignores it.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void createAccountIfAbsent(String accountId, String currency, Instant at) {
        if (accountRepository.existsById(accountId)) {
            return;
        }
        accountRepository.saveAndFlush(new Account(accountId, currency, at));
    }

    @Transactional
    ApplyResult apply(Account account, String accountId, TransactionRequest request) {
        Instant now = Instant.now();

        BigDecimal delta = request.type() == TransactionType.CREDIT
                ? request.amount()
                : request.amount().negate();
        account.applyDelta(delta, now);
        Account saved = accountRepository.save(account);

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

        return new ApplyResult(saved, transaction);
    }
}

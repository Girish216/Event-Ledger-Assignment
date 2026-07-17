package com.eventledger.accountservice.repository;

import com.eventledger.accountservice.domain.AccountTransaction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AccountTransactionRepository extends JpaRepository<AccountTransaction, Long> {

    Optional<AccountTransaction> findByEventId(String eventId);

    List<AccountTransaction> findTop50ByAccountIdOrderByEventTimestampDesc(String accountId);
}

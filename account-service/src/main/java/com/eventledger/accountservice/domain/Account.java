package com.eventledger.accountservice.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "accounts")
public class Account {

    @Id
    @Column(name = "account_id")
    private String accountId;

    @Column(nullable = false)
    private BigDecimal balance = BigDecimal.ZERO;

    private String currency;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private Long version;

    protected Account() {
    }

    public Account(String accountId, String currency, Instant now) {
        this.accountId = accountId;
        this.currency = currency;
        this.balance = BigDecimal.ZERO;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void applyDelta(BigDecimal delta, Instant now) {
        this.balance = this.balance.add(delta);
        this.updatedAt = now;
    }

    public String getAccountId() {
        return accountId;
    }

    public BigDecimal getBalance() {
        return balance;
    }

    public String getCurrency() {
        return currency;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}

package com.eventledger.accountservice.service;

import com.eventledger.accountservice.domain.Account;
import com.eventledger.accountservice.domain.AccountTransaction;

record ApplyResult(Account account, AccountTransaction transaction) {
}

package com.eventledger.eventgateway.controller;

import com.eventledger.eventgateway.dto.BalanceResponse;
import com.eventledger.eventgateway.service.EventService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Balance is not listed among the Gateway endpoints in the spec's endpoint table, but section 6
 * (Graceful Degradation) requires the Gateway to return a clear error when a balance query can't
 * reach the Account Service. Since the Account Service is internal-only, the Gateway must expose
 * the read itself.
 */
@RestController
@RequestMapping("/accounts")
public class AccountProxyController {

    private final EventService eventService;

    public AccountProxyController(EventService eventService) {
        this.eventService = eventService;
    }

    @GetMapping("/{accountId}/balance")
    public BalanceResponse getBalance(@PathVariable String accountId) {
        return eventService.getBalance(accountId);
    }
}

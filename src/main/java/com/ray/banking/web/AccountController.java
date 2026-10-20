package com.ray.banking.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ray.banking.service.AccountService;
import com.ray.banking.service.IdempotencyService;
import com.ray.banking.web.dto.Requests.AmountRequest;
import com.ray.banking.web.dto.Requests.CreateAccountRequest;
import com.ray.banking.web.dto.Requests.TransferRequest;
import com.ray.banking.web.dto.Responses.AccountResponse;
import com.ray.banking.web.dto.Responses.TransactionResponse;
import com.ray.banking.web.dto.Responses.TransferResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.io.UncheckedIOException;
import java.util.List;

@RestController
@RequestMapping("/api")
public class AccountController {

    static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    static final String IDEMPOTENT_REPLAYED = "Idempotent-Replayed";

    private final AccountService accountService;
    private final IdempotencyService idempotencyService;
    private final ObjectMapper objectMapper;

    public AccountController(AccountService accountService, IdempotencyService idempotencyService,
                             ObjectMapper objectMapper) {
        this.accountService = accountService;
        this.idempotencyService = idempotencyService;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/accounts")
    @ResponseStatus(HttpStatus.CREATED)
    public AccountResponse createAccount(@Valid @RequestBody CreateAccountRequest request) {
        return AccountResponse.from(
                accountService.createAccount(request.ownerName(), request.initialDeposit()));
    }

    @GetMapping("/accounts")
    public List<AccountResponse> listAccounts() {
        return accountService.listAccounts().stream().map(AccountResponse::from).toList();
    }

    @GetMapping("/accounts/{id}")
    public AccountResponse getAccount(@PathVariable Long id) {
        return AccountResponse.from(accountService.getAccount(id));
    }

    @PostMapping("/accounts/{id}/deposit")
    public AccountResponse deposit(@PathVariable Long id, @Valid @RequestBody AmountRequest request) {
        return AccountResponse.from(accountService.deposit(id, request.amount()));
    }

    @PostMapping("/accounts/{id}/withdraw")
    public AccountResponse withdraw(@PathVariable Long id, @Valid @RequestBody AmountRequest request) {
        return AccountResponse.from(accountService.withdraw(id, request.amount()));
    }

    @GetMapping("/accounts/{id}/transactions")
    public List<TransactionResponse> getTransactions(@PathVariable Long id) {
        return accountService.getTransactions(id).stream().map(TransactionResponse::from).toList();
    }

    /**
     * With an {@code Idempotency-Key} header, a retried request (same key, same
     * body) gets the original response back, marked {@code Idempotent-Replayed:
     * true}, and moves no money. Without the header every request is a new
     * transfer.
     */
    @PostMapping("/transfers")
    public ResponseEntity<TransferResponse> transfer(
            @RequestHeader(name = IDEMPOTENCY_KEY, required = false) String idempotencyKey,
            @Valid @RequestBody TransferRequest request) {
        if (idempotencyKey == null) {
            return ResponseEntity.ok(executeTransfer(request));
        }
        IdempotencyService.Result result = idempotencyService.execute(
                idempotencyKey, describe(request), () -> toJson(executeTransfer(request)));
        return ResponseEntity.ok()
                .header(IDEMPOTENT_REPLAYED, String.valueOf(result.replayed()))
                .body(fromJson(result.responseBody()));
    }

    private TransferResponse executeTransfer(TransferRequest request) {
        AccountService.Transfer result = accountService.transfer(
                request.fromAccountId(), request.toAccountId(), request.amount());
        return new TransferResponse(
                AccountResponse.from(result.from()), AccountResponse.from(result.to()));
    }

    /** What makes two requests "the same": 10 and 10.00 are the same amount. */
    private static String describe(TransferRequest request) {
        return "POST /api/transfers from=%d to=%d amount=%s".formatted(
                request.fromAccountId(), request.toAccountId(),
                request.amount().stripTrailingZeros().toPlainString());
    }

    private String toJson(TransferResponse response) {
        try {
            return objectMapper.writeValueAsString(response);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }

    private TransferResponse fromJson(String json) {
        try {
            return objectMapper.readValue(json, TransferResponse.class);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }
}

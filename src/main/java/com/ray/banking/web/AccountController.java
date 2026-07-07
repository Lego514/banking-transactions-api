package com.ray.banking.web;

import com.ray.banking.service.AccountService;
import com.ray.banking.web.dto.Requests.AmountRequest;
import com.ray.banking.web.dto.Requests.CreateAccountRequest;
import com.ray.banking.web.dto.Requests.TransferRequest;
import com.ray.banking.web.dto.Responses.AccountResponse;
import com.ray.banking.web.dto.Responses.TransactionResponse;
import com.ray.banking.web.dto.Responses.TransferResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api")
public class AccountController {

    private final AccountService accountService;

    public AccountController(AccountService accountService) {
        this.accountService = accountService;
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

    @PostMapping("/transfers")
    public TransferResponse transfer(@Valid @RequestBody TransferRequest request) {
        AccountService.Transfer result = accountService.transfer(
                request.fromAccountId(), request.toAccountId(), request.amount());
        return new TransferResponse(
                AccountResponse.from(result.from()), AccountResponse.from(result.to()));
    }
}

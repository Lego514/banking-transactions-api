package com.ray.banking.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class AccountControllerTest {

    @Autowired
    private MockMvc mockMvc;

    private long createAccount(String ownerName, String initialDeposit) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ownerName\":\"%s\",\"initialDeposit\":%s}"
                                .formatted(ownerName, initialDeposit)))
                .andExpect(status().isCreated())
                .andReturn();
        String body = result.getResponse().getContentAsString();
        return Long.parseLong(body.replaceAll(".*\"id\":(\\d+).*", "$1"));
    }

    @Test
    void createAccount_returns201WithAccountBody() throws Exception {
        mockMvc.perform(post("/api/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ownerName\":\"Ray\",\"initialDeposit\":100.00}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.ownerName").value("Ray"))
                .andExpect(jsonPath("$.balance").value(100.00));
    }

    @Test
    void createAccount_blankOwnerName_returns400ValidationError() throws Exception {
        mockMvc.perform(post("/api/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ownerName\":\"  \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void getAccount_unknownId_returns404WithErrorCode() throws Exception {
        mockMvc.perform(get("/api/accounts/99999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_FOUND"));
    }

    @Test
    void withdraw_insufficientFunds_returns409() throws Exception {
        long id = createAccount("Poor Bob", "5.00");

        mockMvc.perform(post("/api/accounts/" + id + "/withdraw")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":10.00}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_FUNDS"))
                .andExpect(jsonPath("$.message", containsString("insufficient funds")));
    }

    @Test
    void deposit_negativeAmount_returns400() throws Exception {
        long id = createAccount("Alice", "0");

        mockMvc.perform(post("/api/accounts/" + id + "/deposit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":-5}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void transfer_endToEnd_movesMoneyBetweenAccounts() throws Exception {
        long from = createAccount("Sender", "300.00");
        long to = createAccount("Receiver", "0");

        mockMvc.perform(post("/api/transfers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fromAccountId\":%d,\"toAccountId\":%d,\"amount\":120.50}"
                                .formatted(from, to)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.from.balance").value(179.50))
                .andExpect(jsonPath("$.to.balance").value(120.50));

        mockMvc.perform(get("/api/accounts/" + to + "/transactions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].type").value("TRANSFER_IN"))
                .andExpect(jsonPath("$[0].counterpartyAccountId").value((int) from));
    }

    private ResultActions transferWithKey(String key, long from, long to, String amount) throws Exception {
        return mockMvc.perform(post("/api/transfers")
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"fromAccountId\":%d,\"toAccountId\":%d,\"amount\":%s}".formatted(from, to, amount)));
    }

    @Test
    void transfer_retriedWithSameIdempotencyKey_movesMoneyOnceAndReplaysTheResponse() throws Exception {
        long from = createAccount("Sender", "300.00");
        long to = createAccount("Receiver", "0");
        String key = UUID.randomUUID().toString();

        String firstBody = transferWithKey(key, from, to, "120.50")
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replayed", "false"))
                .andReturn().getResponse().getContentAsString();

        transferWithKey(key, from, to, "120.50")
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(content().string(firstBody));

        mockMvc.perform(get("/api/accounts/" + from))
                .andExpect(jsonPath("$.balance").value(179.50));
        mockMvc.perform(get("/api/accounts/" + to + "/transactions"))
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void transfer_sameIdempotencyKeyWithDifferentRequest_returns422AndMovesNothingMore() throws Exception {
        long from = createAccount("Sender", "300.00");
        long to = createAccount("Receiver", "0");
        String key = UUID.randomUUID().toString();

        transferWithKey(key, from, to, "100.00").andExpect(status().isOk());

        transferWithKey(key, from, to, "999.00")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));

        mockMvc.perform(get("/api/accounts/" + from))
                .andExpect(jsonPath("$.balance").value(200.00));
    }

    @Test
    void transfer_failedWithIdempotencyKey_canBeRetriedOnceTheCauseIsFixed() throws Exception {
        long from = createAccount("Short", "50.00");
        long to = createAccount("Payee", "0");
        String key = UUID.randomUUID().toString();

        transferWithKey(key, from, to, "100.00")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_FUNDS"));

        mockMvc.perform(post("/api/accounts/" + from + "/deposit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":100.00}"))
                .andExpect(status().isOk());

        // The failed attempt rolled its key back with it, so this is a real
        // transfer, not a replay of the 409.
        transferWithKey(key, from, to, "100.00")
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replayed", "false"))
                .andExpect(jsonPath("$.from.balance").value(50.00));
    }

    @Test
    void transfer_idempotencyKeyTooLong_returns400() throws Exception {
        long from = createAccount("Sender", "300.00");
        long to = createAccount("Receiver", "0");

        transferWithKey("k".repeat(256), from, to, "10.00")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_TRANSACTION"));
    }
}

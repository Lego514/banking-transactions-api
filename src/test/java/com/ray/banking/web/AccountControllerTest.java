package com.ray.banking.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
}

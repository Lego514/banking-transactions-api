package com.ray.banking.web;

import com.ray.banking.service.AccountService;
import com.ray.banking.service.IdempotencyService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Failures on our side: the database down, or a plain bug. The service is
 * mocked to fail on purpose, because a working app never takes these paths.
 * The client gets the usual JSON body and a 500, and none of the internal
 * detail in the exception (hosts, SQL, class names) leaks into it.
 */
@WebMvcTest(AccountController.class)
class UnexpectedErrorTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AccountService accountService;

    @MockitoBean
    private IdempotencyService idempotencyService;

    @Test
    void databaseFailure_returns500WithoutLeakingDetails() throws Exception {
        when(accountService.getAccount(1L))
                .thenThrow(new DataAccessResourceFailureException("connection to db-internal:5432 refused"));

        mockMvc.perform(get("/api/accounts/1"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("DATABASE_ERROR"))
                .andExpect(jsonPath("$.message", not(containsString("db-internal"))));
    }

    @Test
    void unexpectedBug_returns500WithoutLeakingDetails() throws Exception {
        when(accountService.getAccount(1L))
                .thenThrow(new IllegalStateException("ledger cache corrupted at node 7"));

        mockMvc.perform(get("/api/accounts/1"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message", not(containsString("ledger cache"))));
    }
}

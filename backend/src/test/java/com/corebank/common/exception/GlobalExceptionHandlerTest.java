package com.corebank.common.exception;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

class GlobalExceptionHandlerTest {

    @RestController
    static class FailingController {
        @GetMapping("/business")
        void business() {
            throw new BusinessException(ErrorCode.INSUFFICIENT_BALANCE);
        }

        @GetMapping("/unexpected")
        void unexpected() {
            throw new IllegalStateException("db password=Secret123! leaked");
        }
    }

    private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new FailingController())
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    @Test
    void businessExceptionUsesErrorCodeStatusAndFormat() throws Exception {
        mockMvc.perform(get("/business"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_BALANCE"))
                .andExpect(jsonPath("$.message").value(ErrorCode.INSUFFICIENT_BALANCE.getMessage()))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    void unexpectedExceptionHidesInternalDetails() throws Exception {
        mockMvc.perform(get("/unexpected"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(content().string(not(containsString("Secret123!"))));
    }
}

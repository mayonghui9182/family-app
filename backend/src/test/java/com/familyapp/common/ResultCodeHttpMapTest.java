package com.familyapp.common;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ResultCodeHttpMapTest {
    @Test
    void newThreeCodesMatchPrefix() {
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, ResultCode.AI_PROPOSAL_ALREADY_FINALIZED.getHttpStatus());
        assertEquals(50311, ResultCode.AI_PROPOSAL_ALREADY_FINALIZED.getCode());
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, ResultCode.AI_PROPOSAL_NO_CONTEXT.getHttpStatus());
        assertEquals(50312, ResultCode.AI_PROPOSAL_NO_CONTEXT.getCode());
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, ResultCode.AI_PROPOSAL_EMPTY.getHttpStatus());
        assertEquals(50313, ResultCode.AI_PROPOSAL_EMPTY.getCode());
    }
}

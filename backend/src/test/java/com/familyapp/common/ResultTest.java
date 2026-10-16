package com.familyapp.common;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ResultTest {

    @Test
    void ok_returns_envelope_with_code_0_and_data() {
        Result<String> r = Result.ok("hello");
        assertEquals(0, r.getCode());
        assertEquals("操作成功", r.getMsg());
        assertEquals("hello", r.getData());
    }

    @Test
    void error_uses_resultCode_code_and_msg() {
        Result<Object> r = Result.error(ResultCode.TASK_NOT_FOUND);
        assertEquals(40401, r.getCode());
        assertEquals("任务不存在", r.getMsg());
        assertNull(r.getData());
    }
}

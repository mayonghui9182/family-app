package com.familyapp.common;

public class ApiException extends RuntimeException {
    private final ResultCode code;

    public ApiException(ResultCode code) {
        super(code.getMsg());
        this.code = code;
    }

    public ApiException(ResultCode code, String customMessage) {
        super(customMessage);
        this.code = code;
    }

    public ResultCode getCode() { return code; }
}

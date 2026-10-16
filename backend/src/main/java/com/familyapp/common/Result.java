package com.familyapp.common;

public class Result<T> {
    private int code;
    private String msg;
    private T data;

    public Result() {}

    public Result(int code, String msg, T data) {
        this.code = code;
        this.msg = msg;
        this.data = data;
    }

    public static <T> Result<T> ok(T data) {
        return new Result<>(ResultCode.OK.getCode(), ResultCode.OK.getMsg(), data);
    }

    public static Result<Void> ok() {
        return new Result<>(ResultCode.OK.getCode(), ResultCode.OK.getMsg(), null);
    }

    public static <T> Result<T> error(ResultCode rc) {
        return new Result<>(rc.getCode(), rc.getMsg(), null);
    }

    public static <T> Result<T> error(ResultCode rc, String customMsg) {
        return new Result<>(rc.getCode(), customMsg, null);
    }

    /** ResultException 使用：无需 ResultCode 枚举，直接传 int code + msg */
    public static <T> Result<T> error(int code, String msg) {
        return new Result<>(code, msg, null);
    }

    public int getCode() { return code; }
    public void setCode(int code) { this.code = code; }
    public String getMsg() { return msg; }
    public void setMsg(String msg) { this.msg = msg; }
    public T getData() { return data; }
    public void setData(T data) { this.data = data; }
}

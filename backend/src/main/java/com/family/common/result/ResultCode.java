package com.family.common.result;

import lombok.Getter;

/**
 * 响应码枚举
 */
@Getter
public enum ResultCode {

    /**
     * 成功
     */
    SUCCESS(200, "操作成功"),

    /**
     * 通用错误
     */
    ERROR(500, "系统内部错误"),

    /**
     * 参数错误
     */
    PARAM_ERROR(400, "参数错误"),

    /**
     * 未授权
     */
    UNAUTHORIZED(401, "未授权，请先登录"),

    /**
     * 没有权限
     */
    FORBIDDEN(403, "没有权限访问"),

    /**
     * 资源不存在
     */
    NOT_FOUND(404, "资源不存在"),

    /**
     * 用户不存在
     */
    USER_NOT_FOUND(1001, "用户不存在"),

    /**
     * 密码错误
     */
    PASSWORD_ERROR(1002, "密码错误"),

    /**
     * 用户名已存在
     */
    USERNAME_EXIST(1003, "用户名已存在"),

    /**
     * Token 失效
     */
    TOKEN_INVALID(1004, "Token 无效或已过期");

    /**
     * 响应码
     */
    private final Integer code;

    /**
     * 响应消息
     */
    private final String message;

    ResultCode(Integer code, String message) {
        this.code = code;
        this.message = message;
    }

}

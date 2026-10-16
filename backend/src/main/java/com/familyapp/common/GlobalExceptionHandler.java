package com.familyapp.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理器。所有 ApiException 按 ResultCode.httpStatus 返回对应 HTTP 状态码。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Result<Object>> handleApi(ApiException ex) {
        ResultCode rc = ex.getCode();
        String message = ex.getMessage();
        return new ResponseEntity<>(Result.error(rc, message), rc.getHttpStatus());
    }

    /**
     * ResultException 处理：按 code 前 3 位映射 HTTP 状态码。
     * - 400xx → BAD_REQUEST
     * - 401xx / 403xx → FORBIDDEN
     * - 404xx → NOT_FOUND
     * - 409xx → CONFLICT
     * - 503xx → SERVICE_UNAVAILABLE
     * - 其他 → BAD_REQUEST（fallback）
     */
    @ExceptionHandler(ResultException.class)
    public ResponseEntity<Result<Object>> handleResult(ResultException ex) {
        int code = ex.getCode();
        HttpStatus status;
        int prefix = code / 100;
        if (prefix == 401 || prefix == 403) {
            status = HttpStatus.FORBIDDEN;
        } else if (prefix == 404) {
            status = HttpStatus.NOT_FOUND;
        } else if (prefix == 409) {
            status = HttpStatus.CONFLICT;
        } else if (prefix == 503) {
            status = HttpStatus.SERVICE_UNAVAILABLE;
        } else if (code == 501 || prefix == 501) {
            // B-4 /profiles/patch-many MVP 占位：JSON code=501 + HTTP 501 Not Implemented
            status = HttpStatus.NOT_IMPLEMENTED;
        } else {
            status = HttpStatus.BAD_REQUEST;
        }
        return new ResponseEntity<>(Result.error(code, ex.getMessage()), status);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Result<Object>> handleValidation(MethodArgumentNotValidException ex) {
        String msg = ex.getBindingResult().getFieldErrors().isEmpty()
            ? ResultCode.BAD_REQUEST_VALIDATION.getMsg()
            : ex.getBindingResult().getFieldErrors().get(0).getDefaultMessage();
        return new ResponseEntity<>(
            Result.error(ResultCode.BAD_REQUEST_VALIDATION, msg),
            HttpStatus.BAD_REQUEST);
    }

    /** 缺少必填请求参数（如 family_id）→ 400 + 参数校验失败信封。 */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Result<Object>> handleMissingParam(MissingServletRequestParameterException ex) {
        return new ResponseEntity<>(
            Result.error(ResultCode.BAD_REQUEST_VALIDATION, "缺少必填参数：" + ex.getParameterName()),
            HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Result<Object>> handleIllegalArgument(IllegalArgumentException ex) {
        return new ResponseEntity<>(
            Result.error(ResultCode.BAD_REQUEST_VALIDATION, ex.getMessage()),
            HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Object>> handleFallback(Exception ex) {
        log.error("未捕获异常", ex);
        return new ResponseEntity<>(
            Result.error(ResultCode.INTERNAL_ERROR),
            HttpStatus.INTERNAL_SERVER_ERROR);
    }
}
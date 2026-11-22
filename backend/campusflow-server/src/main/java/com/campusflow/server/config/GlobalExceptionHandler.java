package com.campusflow.server.config;

import com.campusflow.common.error.ErrorCode;
import com.campusflow.common.exception.BizException;
import com.campusflow.common.result.ApiResult;
import com.campusflow.common.util.TraceUtils;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.stream.Collectors;

/**
 * 全局异常处理（统一响应结构 + TraceId 贯穿）。
 * 业务异常返回业务码；参数校验 40001；未授权 40101；系统异常 50000（不泄漏堆栈细节）。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BizException.class)
    public ApiResult<Void> handleBiz(BizException e) {
        log.warn("业务异常 code={} message={} traceId={}", e.getCode(), e.getMessage(), currentTraceId());
        ApiResult<Void> result = ApiResult.fail(e.getCode(), e.getMessage());
        result.setTraceId(currentTraceId());
        return result;
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, BindException.class})
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResult<Void> handleValidation(BindException e) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .collect(Collectors.joining("; "));
        ApiResult<Void> result = ApiResult.fail(ErrorCode.PARAM_ERROR.getCode(), detail);
        result.setTraceId(currentTraceId());
        return result;
    }

    @ExceptionHandler(ConstraintViolationException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResult<Void> handleConstraint(ConstraintViolationException e) {
        ApiResult<Void> result = ApiResult.fail(ErrorCode.PARAM_ERROR.getCode(), e.getMessage());
        result.setTraceId(currentTraceId());
        return result;
    }

    @ExceptionHandler({MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class})
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResult<Void> handleParam(Exception e) {
        ApiResult<Void> result = ApiResult.fail(ErrorCode.PARAM_ERROR.getCode(), e.getMessage());
        result.setTraceId(currentTraceId());
        return result;
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    @ResponseStatus(HttpStatus.METHOD_NOT_ALLOWED)
    public ApiResult<Void> handleMethodNotSupported(HttpRequestMethodNotSupportedException e) {
        ApiResult<Void> result = ApiResult.fail(ErrorCode.METHOD_NOT_ALLOWED);
        result.setTraceId(currentTraceId());
        return result;
    }

    @ExceptionHandler(NoResourceFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ApiResult<Void> handleNotFound(NoResourceFoundException e) {
        ApiResult<Void> result = ApiResult.fail(ErrorCode.NOT_FOUND);
        result.setTraceId(currentTraceId());
        return result;
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ApiResult<Void> handleUnknown(Exception e) {
        // 统一 500：记录完整堆栈（带 traceId），对外只返回通用文案（防内部信息泄漏）
        log.error("系统异常 traceId={}", currentTraceId(), e);
        ApiResult<Void> result = ApiResult.fail(ErrorCode.SYSTEM_ERROR);
        result.setTraceId(currentTraceId());
        return result;
    }

    private String currentTraceId() {
        return MDC.get(TraceUtils.MDC_KEY);
    }
}

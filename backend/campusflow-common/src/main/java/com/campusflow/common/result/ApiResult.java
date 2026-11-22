package com.campusflow.common.result;

import com.campusflow.common.error.ErrorCode;
import lombok.Data;

import java.io.Serializable;

/**
 * 统一响应结构。
 * <p>所有 REST 接口返回该结构：code=0 表示成功，非 0 见 {@link ErrorCode}；
 * traceId 用于全链路日志排查，由 TraceIdFilter 注入。
 */
@Data
public class ApiResult<T> implements Serializable {

    private int code;
    private String message;
    private T data;
    private String traceId;

    public static <T> ApiResult<T> ok(T data) {
        ApiResult<T> r = new ApiResult<>();
        r.code = ErrorCode.SUCCESS.getCode();
        r.message = "success";
        r.data = data;
        return r;
    }

    public static <T> ApiResult<T> ok() {
        return ok(null);
    }

    public static <T> ApiResult<T> fail(ErrorCode errorCode) {
        return fail(errorCode.getCode(), errorCode.getMessage());
    }

    public static <T> ApiResult<T> fail(int code, String message) {
        ApiResult<T> r = new ApiResult<>();
        r.code = code;
        r.message = message;
        return r;
    }

    public boolean isSuccess() {
        return code == ErrorCode.SUCCESS.getCode();
    }
}

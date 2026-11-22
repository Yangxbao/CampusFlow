package com.campusflow.common.exception;

import com.campusflow.common.error.ErrorCode;
import lombok.Getter;

/**
 * 业务异常。Controller 层由全局异常处理器统一转换为 ApiResult，业务代码直接抛出即可。
 */
@Getter
public class BizException extends RuntimeException {

    private final int code;

    public BizException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.code = errorCode.getCode();
    }

    public BizException(ErrorCode errorCode, String detail) {
        super(detail);
        this.code = errorCode.getCode();
    }

    public static BizException of(ErrorCode errorCode) {
        return new BizException(errorCode);
    }

    /** 带自定义详情的错误（detail 覆盖默认 message） */
    public static BizException of(ErrorCode errorCode, String detail) {
        return new BizException(errorCode, detail);
    }
}

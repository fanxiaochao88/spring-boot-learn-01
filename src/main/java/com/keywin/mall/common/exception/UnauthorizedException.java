package com.keywin.mall.common.exception;

import com.keywin.mall.common.api.ResultCode;

public class UnauthorizedException extends RuntimeException{
    private final int code;

    public UnauthorizedException(ResultCode resultCode) {
        super(resultCode.getMessage());
        this.code = resultCode.getCode();
    }

    public UnauthorizedException(ResultCode resultCode, String message) {
        super(message);
        this.code = resultCode.getCode();
    }
}

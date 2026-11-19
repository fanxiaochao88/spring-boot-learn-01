package com.keywin.mall.common.exception;

import com.keywin.mall.common.api.ResultCode;
import lombok.Getter;

@Getter
public class ForbiddenException extends RuntimeException {

    private final int code;

    public ForbiddenException(ResultCode resultCode) {
        super(resultCode.getMessage());
        this.code = resultCode.getCode();
    }

    public ForbiddenException(ResultCode resultCode, String message) {
        super(message);
        this.code = resultCode.getCode();
    }

}

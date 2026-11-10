package com.keywin.mall.common.exception;

import com.keywin.mall.common.api.ResultCode;
import lombok.Getter;

@Getter
public class BusinessException extends RuntimeException{

    private final int code;

    public BusinessException(ResultCode resultCode, String message) {
        super(message); // 调用父类的构造方法, message由父类保管
        this.code = resultCode.getCode();
    }

    public BusinessException(ResultCode resultCode) {
        super(resultCode.getMessage()); // 调用父类的构造方法, message由父类保管
        this.code = resultCode.getCode();
    }
}

package com.keywin.mall.common.api;

import lombok.Getter;

@Getter
public enum ResultCode {
    SUCCESS(0, "成功"),
    BAD_REQUEST(40000, "请求参数错误"),
    UNAUTHORIZED(40100, "未登录或登录已过期"),
    FORBIDDEN(40300, "没有权限"),
    NOT_FOUND(40400, "资源/接口不存在"),
    CONFLICT(40900, "数据冲突"),
    INTERNAL_ERROR(50000, "系统内部错误"),

    LOGIN_FAILED(40101, "用户名或密码错误"),
    TOKEN_MISSING(40102, "未携带登录凭证"),
    TOKEN_INVALID(40103, "登录凭证无效或已失效"),
    TOKEN_EXPIRED(40104, "登录已过期，请重新登录"),
    ACCOUNT_DISABLED(40105, "账号已被禁用"),

    // 业务错误码：1xxxx 表示商品模块
    PRODUCT_NOT_FOUND(10001, "商品不存在"),
    PRODUCT_STOCK_NOT_ENOUGH(10002, "商品库存不足"),
    PRODUCT_OFF_SHELF(10003, "商品已下架"),

    USER_NOT_FOUND(20001, "用户不存在");

    private final int code;
    private final String message;

    ResultCode(int code, String message) {
        this.code = code;
        this.message = message;
    }
}

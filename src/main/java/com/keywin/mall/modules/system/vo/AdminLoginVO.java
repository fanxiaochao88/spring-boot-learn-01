package com.keywin.mall.modules.system.vo;

import lombok.Data;

@Data
public class AdminLoginVO {

    private String token; // Token
    private String tokenType = "Bearer";

    private long expiresIn; // 有效期(秒)

    private AdminUserVO user; // 用户信息
}

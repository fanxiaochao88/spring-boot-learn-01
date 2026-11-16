package com.keywin.mall.modules.system.vo;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class AdminUserVO {
    private Long id;
    private String username;
    private String nickname;
    private String phone;
    private Integer status;

    private LocalDateTime lastLoginTime;
    private String lastLoginIp;
}

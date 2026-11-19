package com.keywin.mall.modules.system.vo;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class SysPermissionVO {

    private Long id;
    private String name;
    private String code;
    private String module;
    private String description;

    private Integer status;

    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}

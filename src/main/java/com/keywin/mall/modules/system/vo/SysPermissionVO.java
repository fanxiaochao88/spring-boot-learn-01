package com.keywin.mall.modules.system.vo;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class SysPermissionVO {

    private Long id;
    private String name;
    private String code;
    private String description;

    private Integer status;

}

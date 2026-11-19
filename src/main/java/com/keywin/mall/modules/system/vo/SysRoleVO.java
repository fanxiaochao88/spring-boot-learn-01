package com.keywin.mall.modules.system.vo;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class SysRoleVO {
    private Long id;
    private String name;
    private String code;
    private String description;

    private Integer status;

    private List<SysPermissionVO> permissionList;

    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}

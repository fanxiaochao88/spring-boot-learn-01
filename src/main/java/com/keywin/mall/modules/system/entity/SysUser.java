package com.keywin.mall.modules.system.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@TableName("sys_user")
public class SysUser {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String username;

    private String passwordHash;

    private String nickname;

    private String phone;

    private String email;

    /** 1:正常 0:禁用  */
    private Integer status;

    /** 令牌版本号, +1 表示该用户所有旧令牌失效  */
    private Integer tokenVersion;

    private LocalDateTime lastLoginTime;

    private String lastLoginIp;

    @Version
    private Integer version;

    @TableLogic
    private Integer deleted;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime; // INSERT 填充
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime; // INSERT_UPDATE 填充
}

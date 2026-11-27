# 第 04 章 角色与权限模块 CRUD

> 本章目标：把角色、权限的增删改查跑通。本章还不做“给用户分配角色”，那是第 10 章的事。现在先把三张核心表和两张关联表的数据管起来。

---

## 4.1 前置检查

第 03 章完成：

```bash
curl -s "http://localhost:8080/api/users?page=1&size=10"
```

能返回统一 JSON，且 `records` 里没有 `passwordHash`。

数据库里应该已经有：

```sql
USE security_lab;
SELECT COUNT(*) FROM sys_role;             -- 3
SELECT COUNT(*) FROM sys_permission;       -- 12
SELECT COUNT(*) FROM sys_user_role;        -- 3
SELECT COUNT(*) FROM sys_role_permission;  -- 20
```

---

## 4.2 RBAC 模型

```text
┌──────────┐       ┌───────────────┐       ┌──────────┐       ┌────────────────────┐       ┌────────────────┐
│ sys_user │──<    │ sys_user_role │    >──│ sys_role │──<    │ sys_role_permission│    >──│ sys_permission │
└──────────┘       └───────────────┘       └──────────┘       └────────────────────┘       └────────────────┘
   用户                 用户-角色              角色                 角色-权限                   权限
```

三句话记住这个模型：

1. **用户不直接挂权限。** 用户挂角色，角色挂权限。
2. **一个用户可以有多个角色，一个角色可以有多个权限。** 所以关联表是“多对多”。
3. **最终放进 Spring Security 的 authorities 有两类：**
   - 角色：`ROLE_SUPER_ADMIN`
   - 权限：`user:list`

为什么要多一层角色？

- 如果用户直接挂权限，每加一个人都要配一遍，容易漏。
- 角色是“权限套餐”。新人入职给角色，离职收角色，权限自然跟着变。

---

## 4.3 完整目录结构（本章新增部分）

```text
src/main/java/com/keywin/security/modules/
├── role/
│   ├── controller/RoleController.java
│   ├── dto/RoleCreateDTO.java
│   ├── dto/RoleUpdateDTO.java
│   ├── entity/SysRole.java
│   ├── entity/SysUserRole.java
│   ├── mapper/SysRoleMapper.java
│   ├── mapper/SysUserRoleMapper.java
│   ├── service/RoleService.java
│   ├── service/impl/RoleServiceImpl.java
│   └── vo/RoleVO.java
└── permission/
    ├── controller/PermissionController.java
    ├── dto/PermissionCreateDTO.java
    ├── dto/PermissionUpdateDTO.java
    ├── entity/SysPermission.java
    ├── entity/SysRolePermission.java
    ├── mapper/SysPermissionMapper.java
    ├── mapper/SysRolePermissionMapper.java
    ├── service/PermissionService.java
    ├── service/impl/PermissionServiceImpl.java
    └── vo/PermissionVO.java
```

---

## 4.4 先跑通：角色模块

### 4.4.1 实体 `SysRole`

`src/main/java/com/keywin/security/modules/role/entity/SysRole.java`：

```java
package com.keywin.security.modules.role.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@TableName("sys_role")
public class SysRole {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String code;

    private String name;

    private String description;

    private Integer status;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
```

### 4.4.2 关联表实体 `SysUserRole`（说明用）

`src/main/java/com/keywin/security/modules/role/entity/SysUserRole.java`：

```java
package com.keywin.security.modules.role.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 用户-角色关联表。
 * 这张表是联合主键，没有单一 id，所以不用 BaseMapper，
 * 而是在 SysUserRoleMapper 里直接写 @Select / @Insert / @Delete。
 */
@Getter
@Setter
@TableName("sys_user_role")
public class SysUserRole {

    private Long userId;

    private Long roleId;
}
```

这个类本身在本章不会被使用，放在这里是为了让你对照表结构。真正操作关联表的是下面的 Mapper。

### 4.4.3 `SysRoleMapper`

`src/main/java/com/keywin/security/modules/role/mapper/SysRoleMapper.java`：

```java
package com.keywin.security.modules.role.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.keywin.security.modules.role.entity.SysRole;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface SysRoleMapper extends BaseMapper<SysRole> {
}
```

第 05 章会在这里加一个“按用户 ID 查角色编码”的方法。

### 4.4.4 `SysUserRoleMapper`

`src/main/java/com/keywin/security/modules/role/mapper/SysUserRoleMapper.java`：

```java
package com.keywin.security.modules.role.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface SysUserRoleMapper {

    @Select("SELECT role_id FROM sys_user_role WHERE user_id = #{userId}")
    List<Long> selectRoleIdsByUserId(@Param("userId") Long userId);

    @Delete("DELETE FROM sys_user_role WHERE user_id = #{userId}")
    int deleteByUserId(@Param("userId") Long userId);

    @Insert("INSERT INTO sys_user_role(user_id, role_id) VALUES(#{userId}, #{roleId})")
    int insert(@Param("userId") Long userId, @Param("roleId") Long roleId);
}
```

为什么不用 `BaseMapper`？

- `sys_user_role` 是联合主键 `(user_id, role_id)`，没有单一 `id`。
- MyBatis-Plus 的 `BaseMapper` 默认按单一主键操作，用起来别扭。
- 直接写三条 SQL 更清楚，也方便你理解关联表到底怎么查、怎么删。

### 4.4.5 DTO

`src/main/java/com/keywin/security/modules/role/dto/RoleCreateDTO.java`：

```java
package com.keywin.security.modules.role.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class RoleCreateDTO {

    @NotBlank(message = "角色编码不能为空")
    @Size(max = 50, message = "角色编码最长 50 个字符")
    @Pattern(regexp = "^[A-Z][A-Z0-9_]*$",
            message = "角色编码只能由大写字母、数字、下划线组成，且以字母开头")
    private String code;

    @NotBlank(message = "角色名称不能为空")
    @Size(max = 50, message = "角色名称最长 50 个字符")
    private String name;

    @Size(max = 255, message = "描述最长 255 个字符")
    private String description;

    private Integer status = 1;
}
```

`src/main/java/com/keywin/security/modules/role/dto/RoleUpdateDTO.java`：

```java
package com.keywin.security.modules.role.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class RoleUpdateDTO {

    @NotBlank(message = "角色名称不能为空")
    @Size(max = 50, message = "角色名称最长 50 个字符")
    private String name;

    @Size(max = 255, message = "描述最长 255 个字符")
    private String description;

    private Integer status;
}
```

**更新 DTO 里没有 `code`。** 角色编码一旦被权限规则引用，就不应该随便改。要改就新建一个角色，迁移完再删旧的。

### 4.4.6 VO

`src/main/java/com/keywin/security/modules/role/vo/RoleVO.java`：

```java
package com.keywin.security.modules.role.vo;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
public class RoleVO {

    private Long id;

    private String code;

    private String name;

    private String description;

    private Integer status;

    private LocalDateTime createTime;
}
```

### 4.4.7 Service 接口

`src/main/java/com/keywin/security/modules/role/service/RoleService.java`：

```java
package com.keywin.security.modules.role.service;

import com.keywin.security.modules.role.dto.RoleCreateDTO;
import com.keywin.security.modules.role.dto.RoleUpdateDTO;
import com.keywin.security.modules.role.vo.RoleVO;

import java.util.List;

public interface RoleService {

    List<RoleVO> list();

    RoleVO getById(Long id);

    Long create(RoleCreateDTO dto);

    void update(Long id, RoleUpdateDTO dto);

    void delete(Long id);
}
```

### 4.4.8 Service 实现

`src/main/java/com/keywin/security/modules/role/service/impl/RoleServiceImpl.java`：

```java
package com.keywin.security.modules.role.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.keywin.security.common.api.ResultCode;
import com.keywin.security.common.exception.BusinessException;
import com.keywin.security.modules.role.dto.RoleCreateDTO;
import com.keywin.security.modules.role.dto.RoleUpdateDTO;
import com.keywin.security.modules.role.entity.SysRole;
import com.keywin.security.modules.role.mapper.SysRoleMapper;
import com.keywin.security.modules.role.service.RoleService;
import com.keywin.security.modules.role.vo.RoleVO;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class RoleServiceImpl implements RoleService {

    private final SysRoleMapper sysRoleMapper;

    @Override
    public List<RoleVO> list() {
        return sysRoleMapper.selectList(
                        new LambdaQueryWrapper<SysRole>().orderByAsc(SysRole::getId))
                .stream()
                .map(this::toVO)
                .toList();
    }

    @Override
    public RoleVO getById(Long id) {
        return toVO(getRoleOrThrow(id));
    }

    @Override
    @Transactional
    public Long create(RoleCreateDTO dto) {
        Long count = sysRoleMapper.selectCount(
                new LambdaQueryWrapper<SysRole>().eq(SysRole::getCode, dto.getCode()));
        if (count > 0) {
            throw new BusinessException(ResultCode.CONFLICT, "角色编码已存在");
        }

        SysRole role = new SysRole();
        role.setCode(dto.getCode());
        role.setName(dto.getName());
        role.setDescription(dto.getDescription());
        role.setStatus(dto.getStatus() == null ? 1 : dto.getStatus());

        sysRoleMapper.insert(role);
        return role.getId();
    }

    @Override
    @Transactional
    public void update(Long id, RoleUpdateDTO dto) {
        SysRole role = getRoleOrThrow(id);

        role.setName(dto.getName());
        if (dto.getDescription() != null) {
            role.setDescription(dto.getDescription());
        }
        if (dto.getStatus() != null) {
            role.setStatus(dto.getStatus());
        }

        sysRoleMapper.updateById(role);
    }

    @Override
    @Transactional
    public void delete(Long id) {
        getRoleOrThrow(id);
        // sys_user_role、sys_role_permission 都有 ON DELETE CASCADE
        sysRoleMapper.deleteById(id);
    }

    private SysRole getRoleOrThrow(Long id) {
        SysRole role = sysRoleMapper.selectById(id);
        if (role == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "角色不存在");
        }
        return role;
    }

    private RoleVO toVO(SysRole role) {
        RoleVO vo = new RoleVO();
        BeanUtils.copyProperties(role, vo);
        return vo;
    }
}
```

### 4.4.9 Controller

`src/main/java/com/keywin/security/modules/role/controller/RoleController.java`：

```java
package com.keywin.security.modules.role.controller;

import com.keywin.security.common.api.Result;
import com.keywin.security.modules.role.dto.RoleCreateDTO;
import com.keywin.security.modules.role.dto.RoleUpdateDTO;
import com.keywin.security.modules.role.service.RoleService;
import com.keywin.security.modules.role.vo.RoleVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/roles")
@RequiredArgsConstructor
public class RoleController {

    private final RoleService roleService;

    @GetMapping
    public Result<List<RoleVO>> list() {
        return Result.success(roleService.list());
    }

    @GetMapping("/{id}")
    public Result<RoleVO> getById(@PathVariable Long id) {
        return Result.success(roleService.getById(id));
    }

    @PostMapping
    public Result<Long> create(@Valid @RequestBody RoleCreateDTO dto) {
        return Result.success(roleService.create(dto));
    }

    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @Valid @RequestBody RoleUpdateDTO dto) {
        roleService.update(id, dto);
        return Result.success();
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        roleService.delete(id);
        return Result.success();
    }
}
```

---

## 4.5 先跑通：权限模块

权限模块和角色模块几乎对称，只是字段多了 `module`，编码格式是 `模块:动作`。

### 4.5.1 实体 `SysPermission`

`src/main/java/com/keywin/security/modules/permission/entity/SysPermission.java`：

```java
package com.keywin.security.modules.permission.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@TableName("sys_permission")
public class SysPermission {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String code;

    private String name;

    private String module;

    private String description;

    private Integer status;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
```

### 4.5.2 关联表实体 `SysRolePermission`（说明用）

`src/main/java/com/keywin/security/modules/permission/entity/SysRolePermission.java`：

```java
package com.keywin.security.modules.permission.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 角色-权限关联表，同样是联合主键，不用 BaseMapper。
 */
@Getter
@Setter
@TableName("sys_role_permission")
public class SysRolePermission {

    private Long roleId;

    private Long permissionId;
}
```

### 4.5.3 Mapper

`src/main/java/com/keywin/security/modules/permission/mapper/SysPermissionMapper.java`：

```java
package com.keywin.security.modules.permission.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.keywin.security.modules.permission.entity.SysPermission;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface SysPermissionMapper extends BaseMapper<SysPermission> {
}
```

`src/main/java/com/keywin/security/modules/permission/mapper/SysRolePermissionMapper.java`：

```java
package com.keywin.security.modules.permission.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface SysRolePermissionMapper {

    @Select("SELECT permission_id FROM sys_role_permission WHERE role_id = #{roleId}")
    List<Long> selectPermissionIdsByRoleId(@Param("roleId") Long roleId);

    @Delete("DELETE FROM sys_role_permission WHERE role_id = #{roleId}")
    int deleteByRoleId(@Param("roleId") Long roleId);

    @Insert("INSERT INTO sys_role_permission(role_id, permission_id) VALUES(#{roleId}, #{permissionId})")
    int insert(@Param("roleId") Long roleId, @Param("permissionId") Long permissionId);
}
```

### 4.5.4 DTO

`src/main/java/com/keywin/security/modules/permission/dto/PermissionCreateDTO.java`：

```java
package com.keywin.security.modules.permission.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class PermissionCreateDTO {

    @NotBlank(message = "权限编码不能为空")
    @Size(max = 100, message = "权限编码最长 100 个字符")
    @Pattern(regexp = "^[a-z][a-z0-9-]*:[a-z][a-z0-9-]*$",
            message = "权限编码格式应为 模块:动作，例如 user:add")
    private String code;

    @NotBlank(message = "权限名称不能为空")
    @Size(max = 50, message = "权限名称最长 50 个字符")
    private String name;

    @Size(max = 50, message = "所属模块最长 50 个字符")
    private String module;

    @Size(max = 255, message = "描述最长 255 个字符")
    private String description;

    private Integer status = 1;
}
```

`src/main/java/com/keywin/security/modules/permission/dto/PermissionUpdateDTO.java`：

```java
package com.keywin.security.modules.permission.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class PermissionUpdateDTO {

    @NotBlank(message = "权限名称不能为空")
    @Size(max = 50, message = "权限名称最长 50 个字符")
    private String name;

    @Size(max = 50, message = "所属模块最长 50 个字符")
    private String module;

    @Size(max = 255, message = "描述最长 255 个字符")
    private String description;

    private Integer status;
}
```

### 4.5.5 VO

`src/main/java/com/keywin/security/modules/permission/vo/PermissionVO.java`：

```java
package com.keywin.security.modules.permission.vo;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
public class PermissionVO {

    private Long id;

    private String code;

    private String name;

    private String module;

    private String description;

    private Integer status;

    private LocalDateTime createTime;
}
```

### 4.5.6 Service 接口

`src/main/java/com/keywin/security/modules/permission/service/PermissionService.java`：

```java
package com.keywin.security.modules.permission.service;

import com.keywin.security.modules.permission.dto.PermissionCreateDTO;
import com.keywin.security.modules.permission.dto.PermissionUpdateDTO;
import com.keywin.security.modules.permission.vo.PermissionVO;

import java.util.List;

public interface PermissionService {

    List<PermissionVO> list();

    PermissionVO getById(Long id);

    Long create(PermissionCreateDTO dto);

    void update(Long id, PermissionUpdateDTO dto);

    void delete(Long id);
}
```

### 4.5.7 Service 实现

`src/main/java/com/keywin/security/modules/permission/service/impl/PermissionServiceImpl.java`：

```java
package com.keywin.security.modules.permission.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.keywin.security.common.api.ResultCode;
import com.keywin.security.common.exception.BusinessException;
import com.keywin.security.modules.permission.dto.PermissionCreateDTO;
import com.keywin.security.modules.permission.dto.PermissionUpdateDTO;
import com.keywin.security.modules.permission.entity.SysPermission;
import com.keywin.security.modules.permission.mapper.SysPermissionMapper;
import com.keywin.security.modules.permission.service.PermissionService;
import com.keywin.security.modules.permission.vo.PermissionVO;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class PermissionServiceImpl implements PermissionService {

    private final SysPermissionMapper sysPermissionMapper;

    @Override
    public List<PermissionVO> list() {
        return sysPermissionMapper.selectList(
                        new LambdaQueryWrapper<SysPermission>().orderByAsc(SysPermission::getId))
                .stream()
                .map(this::toVO)
                .toList();
    }

    @Override
    public PermissionVO getById(Long id) {
        return toVO(getPermissionOrThrow(id));
    }

    @Override
    @Transactional
    public Long create(PermissionCreateDTO dto) {
        Long count = sysPermissionMapper.selectCount(
                new LambdaQueryWrapper<SysPermission>().eq(SysPermission::getCode, dto.getCode()));
        if (count > 0) {
            throw new BusinessException(ResultCode.CONFLICT, "权限编码已存在");
        }

        SysPermission permission = new SysPermission();
        permission.setCode(dto.getCode());
        permission.setName(dto.getName());
        permission.setModule(dto.getModule());
        permission.setDescription(dto.getDescription());
        permission.setStatus(dto.getStatus() == null ? 1 : dto.getStatus());

        sysPermissionMapper.insert(permission);
        return permission.getId();
    }

    @Override
    @Transactional
    public void update(Long id, PermissionUpdateDTO dto) {
        SysPermission permission = getPermissionOrThrow(id);

        permission.setName(dto.getName());
        if (dto.getModule() != null) {
            permission.setModule(dto.getModule());
        }
        if (dto.getDescription() != null) {
            permission.setDescription(dto.getDescription());
        }
        if (dto.getStatus() != null) {
            permission.setStatus(dto.getStatus());
        }

        sysPermissionMapper.updateById(permission);
    }

    @Override
    @Transactional
    public void delete(Long id) {
        getPermissionOrThrow(id);
        // sys_role_permission 有 ON DELETE CASCADE
        sysPermissionMapper.deleteById(id);
    }

    private SysPermission getPermissionOrThrow(Long id) {
        SysPermission permission = sysPermissionMapper.selectById(id);
        if (permission == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "权限不存在");
        }
        return permission;
    }

    private PermissionVO toVO(SysPermission permission) {
        PermissionVO vo = new PermissionVO();
        BeanUtils.copyProperties(permission, vo);
        return vo;
    }
}
```

### 4.5.8 Controller

`src/main/java/com/keywin/security/modules/permission/controller/PermissionController.java`：

```java
package com.keywin.security.modules.permission.controller;

import com.keywin.security.common.api.Result;
import com.keywin.security.modules.permission.dto.PermissionCreateDTO;
import com.keywin.security.modules.permission.dto.PermissionUpdateDTO;
import com.keywin.security.modules.permission.service.PermissionService;
import com.keywin.security.modules.permission.vo.PermissionVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/permissions")
@RequiredArgsConstructor
public class PermissionController {

    private final PermissionService permissionService;

    @GetMapping
    public Result<List<PermissionVO>> list() {
        return Result.success(permissionService.list());
    }

    @GetMapping("/{id}")
    public Result<PermissionVO> getById(@PathVariable Long id) {
        return Result.success(permissionService.getById(id));
    }

    @PostMapping
    public Result<Long> create(@Valid @RequestBody PermissionCreateDTO dto) {
        return Result.success(permissionService.create(dto));
    }

    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @Valid @RequestBody PermissionUpdateDTO dto) {
        permissionService.update(id, dto);
        return Result.success();
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        permissionService.delete(id);
        return Result.success();
    }
}
```

---

## 4.6 验证

重启应用后，依次执行。

### 4.6.1 角色列表

```bash
curl -s http://localhost:8080/api/roles | python -m json.tool
```

预期返回 3 个角色：`SUPER_ADMIN`、`USER_ADMIN`、`VIEWER`。

### 4.6.2 新增角色

```bash
curl -i -X POST http://localhost:8080/api/roles \
  -H "Content-Type: application/json" \
  -d '{"code":"TEST_ROLE","name":"测试角色","description":"只用于验收","status":1}'
```

预期：

```json
{"code":200,"message":"success","data":4}
```

### 4.6.3 重复角色编码

```bash
curl -i -X POST http://localhost:8080/api/roles \
  -H "Content-Type: application/json" \
  -d '{"code":"TEST_ROLE","name":"重复角色","status":1}'
```

预期：

```text
HTTP/1.1 409
{"code":409,"message":"角色编码已存在","data":null}
```

### 4.6.4 修改角色

```bash
curl -i -X PUT http://localhost:8080/api/roles/4 \
  -H "Content-Type: application/json" \
  -d '{"name":"测试角色-已修改","description":"修改后的描述","status":1}'
```

预期 200。

### 4.6.5 权限列表

```bash
curl -s http://localhost:8080/api/permissions | python -m json.tool
```

预期 12 个权限。

### 4.6.6 新增权限

```bash
curl -i -X POST http://localhost:8080/api/permissions \
  -H "Content-Type: application/json" \
  -d '{"code":"test:demo","name":"测试权限","module":"test","description":"只用于验收","status":1}'
```

预期：

```json
{"code":200,"message":"success","data":13}
```

### 4.6.7 非法权限编码

```bash
curl -i -X POST http://localhost:8080/api/permissions \
  -H "Content-Type: application/json" \
  -d '{"code":"BAD_CODE","name":"非法编码","status":1}'
```

预期 HTTP 400，message 提示格式应为 `模块:动作`。

### 4.6.8 删除角色和权限

```bash
curl -i -X DELETE http://localhost:8080/api/roles/4
curl -i -X DELETE http://localhost:8080/api/permissions/13
```

预期都是 200。关联表里没有引用它们的数据，所以不会报外键错误。

---

## 4.7 常见报错

### 1. 删除角色时报外键约束失败

原因：建表时没有给 `sys_user_role`、`sys_role_permission` 配置 `ON DELETE CASCADE`。

解决：重新执行 `sql/01-schema.sql`，确认外键后面有 `ON DELETE CASCADE`。

### 2. 角色编码重复却返回 500

原因：代码里的唯一性检查没生效，或者数据库唯一索引冲突没被捕获。

解决：确认 `SysRoleServiceImpl.create` 里先 `selectCount`；同时确认 `sys_role.code` 有唯一索引。

### 3. `@Pattern` 不生效，非法编码也能插入

原因：Controller 参数前少了 `@Valid`。

解决：`public Result<Long> create(@Valid @RequestBody RoleCreateDTO dto)`。

### 4. 权限列表顺序乱

原因：没有 `orderByAsc`。

解决：`new LambdaQueryWrapper<SysPermission>().orderByAsc(SysPermission::getId)`。

### 5. 删除权限后，角色还能查到它

原因：`sys_role_permission` 没有级联删除，或者删除的是另一条数据。

解决：检查外键；执行：

```sql
SELECT * FROM sys_role_permission WHERE permission_id = 13;
```

---

## 4.8 本章小结

- RBAC 模型：用户 → 用户角色 → 角色 → 角色权限 → 权限。
- 角色、权限各自 CRUD，编码唯一。
- 关联表是联合主键，用注解 SQL 直接操作，不用 `BaseMapper`。
- 删除角色/权限依赖数据库外键 `ON DELETE CASCADE` 清理关联数据。
- 本章还没有把角色/权限接到 Spring Security，第 05 章会加载它们。

### 自测题

1. 为什么用户不直接挂权限，而要经过角色？
2. 为什么 `sys_user_role` 不用 MyBatis-Plus 的 `BaseMapper`？
3. 为什么角色编码不允许在更新接口里修改？
4. 删除角色时，`sys_user_role` 里的关联数据是谁删的？

<details>
<summary>参考答案</summary>

1. 角色是权限套餐，便于批量分配和回收；直接挂权限容易漏配、难维护。
2. 它是联合主键，没有单一 `id`，`BaseMapper` 按单一主键操作不适用。
3. 角色编码可能被代码、配置、权限规则引用，改了会导致权限判断失效；应该新建角色再迁移。
4. 数据库外键的 `ON DELETE CASCADE`。

</details>

---

下一章：[05-PasswordEncoder与登录认证](05-PasswordEncoder与登录认证.md)

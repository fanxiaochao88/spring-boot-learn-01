package com.keywin.mall.modules.system.controller;

import com.keywin.mall.common.api.Result;
import com.keywin.mall.common.security.RequirePermission;
import com.keywin.mall.modules.system.service.RoleService;
import com.keywin.mall.modules.system.vo.SysRoleVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/admin/roles")
@RequiredArgsConstructor
public class SysRoleController {

    private final RoleService roleService;


    @GetMapping
    @RequirePermission("role:read")
    public Result<List<SysRoleVO>> list() {
        return Result.success(roleService.sysRoleList());
    }
}

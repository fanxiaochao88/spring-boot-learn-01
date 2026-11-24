package com.keywin.mall.modules.system.service;

import com.keywin.mall.common.api.Result;
import com.keywin.mall.common.api.ResultCode;
import com.keywin.mall.common.exception.BusinessException;
import com.keywin.mall.modules.system.mapper.SysPermissionMapper;
import com.keywin.mall.modules.system.vo.SysRoleVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class RoleService {

    private final SysPermissionMapper sysPermissionMapper;

    /**
     * 查询所有角色以及角色的权限
     * @return
     */
    public List<SysRoleVO> sysRoleList() {
        List<SysRoleVO> sysRoleVOS = sysPermissionMapper.selectRolePermission();

        if (sysRoleVOS == null) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "查询失败");
        }

        return sysRoleVOS;
    }
}

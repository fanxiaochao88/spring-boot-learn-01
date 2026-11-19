package com.keywin.mall.modules.lab;

import com.keywin.mall.common.api.Result;
import com.keywin.mall.common.api.ResultCode;
import com.keywin.mall.common.exception.BusinessException;
import com.keywin.mall.modules.system.mapper.SysPermissionMapper;
import com.keywin.mall.modules.system.vo.SysRoleVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("api/lab")
public class LabController {

    @Autowired
    private SysPermissionMapper sysPermissionMapper;

    @GetMapping("hello")
    public Result<String> hello() {
        return Result.success("ok");
    }

    @GetMapping("businessException")
    public Result<Void> businessException() {
        throw new BusinessException(ResultCode.PRODUCT_NOT_FOUND);
    }

    /**
     * 测试mybatis, 查询角色列表以及每个角色的权限列表
     * @return
     */
    @GetMapping("test1")
    public List<SysRoleVO> test() {
        return sysPermissionMapper.selectRolePermission();
    }

    @GetMapping("exception")
    public Result<Void> exception() {
        throw new RuntimeException("runtime exception");
    }
}

package com.keywin.mall.modules.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.keywin.mall.modules.system.entity.SysPermission;
import com.keywin.mall.modules.system.vo.SysRoleVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface SysPermissionMapper extends BaseMapper<SysPermission> {

    /** 查询用户的权限编码 */
    List<String> selectPermissionCodeByUserId(@Param("userId") Long userId);
    /** 查询用户的角色编码 */
    List<String> selectRoleCodeByUserId(@Param("userId") Long userId);
    /** 查询角色的权限编码 */
    List<String> selectPermissionCodeByRoleId(@Param("roleId") Long roleId);
    /** 根据角色ids批量查询权限数据 */
//    List<SysPermission> selectPermissionByRoleIds(@Param("roleIds") List<Long> roleIds);
    /** 查询用户的角色列表和对应的权限编码 */
    List<SysRoleVO> selectRolePermissionByUserId(@Param("userId") Long userId);
}

package com.keywin.mall.modules.system.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.keywin.mall.common.api.ResultCode;
import com.keywin.mall.common.exception.UnauthorizedException;
import com.keywin.mall.common.security.JwtTokenProvider;
import com.keywin.mall.common.security.UserContext;
import com.keywin.mall.common.security.UserContextHolder;
import com.keywin.mall.common.util.IpUtils;
import com.keywin.mall.modules.system.dto.AdminLoginDTO;
import com.keywin.mall.modules.system.entity.SysUser;
import com.keywin.mall.modules.system.mapper.SysUserMapper;
import com.keywin.mall.modules.system.service.AuthService;
import com.keywin.mall.modules.system.service.SysLoginLogService;
import com.keywin.mall.modules.system.vo.AdminLoginVO;
import com.keywin.mall.modules.system.vo.AdminUserVO;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {


    private final SysUserMapper sysUserMapper;
    private final PasswordEncoder passwordEncoder;
    private final SysLoginLogService sysLoginLogService;
    private final JwtTokenProvider jwtTokenProvider;

    @Override
    public AdminLoginVO login(AdminLoginDTO adminLoginDTO, HttpServletRequest httpServletRequest) {
        // 1. 拿ip和userAgent
        String ip = IpUtils.getClientIp(httpServletRequest);
        String userAgent = httpServletRequest.getHeader("User-Agent");

        // 2. 根据用户名查询用户
        SysUser user = sysUserMapper.selectOne(
                new LambdaQueryWrapper<SysUser>()
                        .eq(SysUser::getUsername, adminLoginDTO.getUsername())
        );

        // 3. 边界判断(无该用户或者账号密码不对)
        if (user == null || !passwordEncoder.matches(adminLoginDTO.getPassword(), user.getPasswordHash())) {
            // 当前用户不存在或者是账号密码不匹配
            sysLoginLogService.save(
                    user == null ? null : user.getId(),
                    adminLoginDTO.getUsername(),
                    ip,
                    userAgent,
                    false,
                    "用户名不存在或密码错误"
            );
            throw new UnauthorizedException(ResultCode.LOGIN_FAILED);
        }

        // 4. 判断用户禁用状态
        if (!Integer.valueOf(1).equals(user.getStatus())) {
            sysLoginLogService.save(
                    user.getId(),
                    adminLoginDTO.getUsername(),
                    ip,
                    userAgent,
                    false,
                    "账号已禁用"
            );
            throw new UnauthorizedException(ResultCode.ACCOUNT_DISABLED);
        }
        // 5. 更新用户信息字段
        sysUserMapper.update(
                null,
                new LambdaUpdateWrapper<SysUser>().eq(SysUser::getId, user.getId())
                        .set(SysUser::getLastLoginTime, LocalDateTime.now())
                        .set(SysUser::getLastLoginIp, ip)
        );
        // 6. 生成token
        String token = jwtTokenProvider.generate(
                user.getId(),
                user.getUsername(),
                "ADMIN",
                user.getTokenVersion()
        );
        // 7. 写日志, 返回VO
        sysLoginLogService.save(
                user.getId(),
                adminLoginDTO.getUsername(),
                ip,
                userAgent,
                true,
                null
        );
        log.info("用户登录: id={}, username={}, ip={}, userAgent={}", user.getId(), adminLoginDTO.getUsername(), ip, userAgent);

        AdminLoginVO adminLoginVO = new AdminLoginVO();
        adminLoginVO.setToken(token);
        adminLoginVO.setTokenType("Bearer");
        adminLoginVO.setExpiresIn(jwtTokenProvider.getExpireSeconds());
        adminLoginVO.setUser(toUserVO(user));
        return adminLoginVO;
    }

    @Override
    public void logout() {
        // 1. 拿到LocalThread中的用户信息
        UserContext userContext = UserContextHolder.getUserContext();
        // 2. 查询用户, 将tokenVersion+1
        sysUserMapper.update(
                null,
                new LambdaUpdateWrapper<SysUser>()
                        .eq(SysUser::getId, userContext.userId())
                        .setSql("token_version = token_version + 1")
        );
        log.info("用户登出: id={}, username={}", userContext.userId(), userContext.username());

    }

    @Override
    public AdminUserVO me() {
        UserContext userContext = UserContextHolder.getUserContext();
        SysUser user = sysUserMapper.selectById(userContext.userId());
        if (user == null) {
            throw new UnauthorizedException(ResultCode.USER_NOT_FOUND);
        }
        return toUserVO(user);
    }
    
    private AdminUserVO toUserVO(SysUser user) {
        AdminUserVO adminUserVO = new AdminUserVO();
        BeanUtils.copyProperties(user, adminUserVO);
        return adminUserVO;
    }
}

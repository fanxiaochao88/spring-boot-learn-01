package com.keywin.mall.common.security;

import com.keywin.mall.common.api.ResultCode;
import com.keywin.mall.common.exception.UnauthorizedException;
import com.keywin.mall.config.JwtProperties;
import com.keywin.mall.modules.system.entity.SysUser;
import com.keywin.mall.modules.system.mapper.SysPermissionMapper;
import com.keywin.mall.modules.system.mapper.SysUserMapper;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.ModelAndView;

import java.util.List;
import java.util.Objects;

@Component
@RequiredArgsConstructor
public class JwtAuthInterceptor implements HandlerInterceptor {

    private final JwtTokenProvider jwtTokenProvider;
    private final SysUserMapper sysUserMapper;
    private final SysPermissionMapper sysPermissionMapper;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {

        // 浏览器跨域预检请求直接放过
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        // 是否有token
        String header = request.getHeader("Authorization");
        if (!StringUtils.hasText(header) || !header.startsWith("Bearer ")) {
            throw new UnauthorizedException(ResultCode.TOKEN_MISSING);
        }

        String token = header.substring(7).trim();

        Claims claims;
        Long userId;

        try {
            claims = jwtTokenProvider.parse(token);
            userId = Long.valueOf(claims.getSubject());
        } catch (ExpiredJwtException e) {
            throw new UnauthorizedException(ResultCode.TOKEN_EXPIRED);
        } catch (JwtException e) {
            throw new UnauthorizedException(ResultCode.TOKEN_INVALID);
        }

        // 查询user

        SysUser user = sysUserMapper.selectById(userId);
        if (user == null) {
            throw new UnauthorizedException(ResultCode.TOKEN_INVALID);
        }
        if (!Integer.valueOf(1).equals(user.getStatus())) {
            throw new UnauthorizedException(ResultCode.ACCOUNT_DISABLED);
        }

        Object versionClaims = claims.get("tokenVersion");
        if (!Objects.equals(Integer.valueOf((int)versionClaims), user.getTokenVersion())) {
            throw new UnauthorizedException(ResultCode.TOKEN_INVALID);
        }

        // 获取用户角色
        List<String> roles = sysPermissionMapper.selectRoleCodeByUserId(userId);
        // 获取用户权限
        List<String> permissions = sysPermissionMapper.selectPermissionCodeByUserId(userId);

        // 存入线程内部map, 包含用户信息, 以及角色和权限信息
        UserContextHolder.setUserContext(
                new UserContext(userId, user.getUsername(),
                        "ADMIN",
                        user.getTokenVersion(),
                        roles,
                        permissions)
        );

        return true;
    }

    @Override
    public void postHandle(HttpServletRequest request, HttpServletResponse response, Object handler, @Nullable ModelAndView modelAndView) throws Exception {
        UserContextHolder.clear();
    }
}

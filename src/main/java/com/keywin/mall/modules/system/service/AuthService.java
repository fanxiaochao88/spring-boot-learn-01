package com.keywin.mall.modules.system.service;

import com.keywin.mall.modules.system.dto.AdminLoginDTO;
import com.keywin.mall.modules.system.vo.AdminLoginVO;
import com.keywin.mall.modules.system.vo.AdminUserVO;
import jakarta.servlet.http.HttpServletRequest;

public interface AuthService {
    AdminLoginVO login(AdminLoginDTO adminLoginDTO, HttpServletRequest httpServletRequest);

    void logout();

    AdminUserVO me();
}

package com.keywin.mall.modules.system.controller;

import com.keywin.mall.common.api.Result;
import com.keywin.mall.modules.system.dto.AdminLoginDTO;
import com.keywin.mall.modules.system.service.AuthService;
import com.keywin.mall.modules.system.vo.AdminLoginVO;
import com.keywin.mall.modules.system.vo.AdminUserVO;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@Slf4j
@Validated
@RestController
@RequestMapping("/api/admin/auth")
@RequiredArgsConstructor
public class AdminAuthController {

    private final AuthService authService;

    @PostMapping("/login")
    public Result<AdminLoginVO> login(@Valid @RequestBody AdminLoginDTO adminLoginDTO,
                                      HttpServletRequest request) {
        return Result.success(authService.login(adminLoginDTO, request));
    }

    @PostMapping("/logout")
    public Result<Void> logout() {
        authService.logout();
        return Result.success();
    }

    @GetMapping("/me")
    public Result<AdminUserVO> me() {
        return Result.success(authService.me());
    }
}

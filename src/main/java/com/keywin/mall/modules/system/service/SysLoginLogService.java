package com.keywin.mall.modules.system.service;

import com.keywin.mall.modules.system.entity.SysLoginLog;
import com.keywin.mall.modules.system.mapper.SysLoginLogMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class SysLoginLogService {

    private final SysLoginLogMapper sysLoginLogMapper;

    /**
     * 保存登录日志
     * @param userId
     * @param username
     * @param ip
     * @param userAgent
     * @param success
     * @param failReason
     */
    public void save(
            Long userId,
            String username,
            String ip,
            String userAgent,
            Boolean success,
            String failReason
    ) {
        try {
            SysLoginLog sysLoginLog = new SysLoginLog();
            sysLoginLog.setUserId(userId);
            sysLoginLog.setUsername(username);
            sysLoginLog.setIp(ip);
            sysLoginLog.setUserAgent(truncate(255, userAgent));
            sysLoginLog.setSuccess(success ? 1 : 0);
            sysLoginLog.setFailReason(failReason);
            sysLoginLog.setLoginTime(LocalDateTime.now());
            sysLoginLogMapper.insert(sysLoginLog);
        } catch (Exception e) {
            log.error("写入登录日志失败: username = {}", username, e);
        }
    }

    /**
     * 将字符串截取到指定长度
     * @param maxLength
     * @param value
     * @return
     */
    private String truncate(int maxLength, String value) {
        if (value == null) {
            return null;
        }
        return value.length() > maxLength ? value.substring(0, maxLength) : value;
    }
}

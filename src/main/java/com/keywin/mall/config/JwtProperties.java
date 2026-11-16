package com.keywin.mall.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "mall.security.jwt")
public class JwtProperties {

    private String secret; // 密钥

    private String issuer; // 签发者

    private long expireMinutes = 120; // 过期时间（分钟）
}

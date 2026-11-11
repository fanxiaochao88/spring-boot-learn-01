package com.keywin.mall.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "mall.product")
public class ProductProperties {
    // 单页最大条数, 防止击穿数据库
    private int pageSizeLimit = 100;
    // 库存预警
    private int lowStockThreshold = 10;
}

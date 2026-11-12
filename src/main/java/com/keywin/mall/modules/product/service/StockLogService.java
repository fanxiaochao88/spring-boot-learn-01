package com.keywin.mall.modules.product.service;

public interface StockLogService {
    void decrease(Long productId, int count, String reason);
}

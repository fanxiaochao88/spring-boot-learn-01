package com.keywin.mall.modules.product.service.impl;

import com.keywin.mall.common.api.ResultCode;
import com.keywin.mall.common.exception.BusinessException;
import com.keywin.mall.modules.product.entity.Product;
import com.keywin.mall.modules.product.entity.StockLog;
import com.keywin.mall.modules.product.mapper.ProductMapper;
import com.keywin.mall.modules.product.mapper.StockLogMapper;
import com.keywin.mall.modules.product.service.StockLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Slf4j
@RequiredArgsConstructor
public class StockLogServiceImpl implements StockLogService {
    private final ProductMapper productMapper;
    private final StockLogMapper stockLogMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void decrease(Long productId, int count, String reason) {
        // 1. 查询原商品
        Product product = productMapper.selectById(productId);
        // 2. 边界判断(商品是否存在, 商品是否下架)
        if (product == null) {
            throw new BusinessException(ResultCode.PRODUCT_NOT_FOUND);
        }
        if (!Integer.valueOf(1).equals(product.getStatus())) {
            throw new BusinessException(ResultCode.PRODUCT_OFF_SHELF);
        }
        // 3. 更新商品count
        int affected = productMapper.decreaseStock(productId, count);
        if (affected == 0) {
            throw new BusinessException(ResultCode.PRODUCT_STOCK_NOT_ENOUGH);
        }
        // 4. 构造StockLog实体
        StockLog stockLog = new StockLog();
        stockLog.setProductId(productId);
        stockLog.setChangeAmount(-count);
        stockLog.setBeforeStock(product.getStock());
        stockLog.setAfterStock(product.getStock() - count);
        stockLog.setReason(reason);
        // 5. 写入流水
        stockLogMapper.insert(stockLog);

        log.info("扣减库存: productId={}, count={}, reason={}", productId, count, reason);
    }
}

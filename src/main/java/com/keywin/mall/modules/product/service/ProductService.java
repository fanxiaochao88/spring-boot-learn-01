package com.keywin.mall.modules.product.service;

import com.keywin.mall.modules.product.dto.ProductCreateDTO;
import com.keywin.mall.modules.product.vo.ProductVO;
import jakarta.validation.Valid;

public interface ProductService {
    ProductVO create(@Valid ProductCreateDTO productCreateDTO);

    ProductVO getById(Long id);
}

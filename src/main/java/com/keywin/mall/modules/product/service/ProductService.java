package com.keywin.mall.modules.product.service;

import com.keywin.mall.common.api.PageResult;
import com.keywin.mall.modules.product.dto.ProductCreateDTO;
import com.keywin.mall.modules.product.dto.ProductQueryDTO;
import com.keywin.mall.modules.product.dto.ProductUpdateDTO;
import com.keywin.mall.modules.product.vo.ProductVO;
import jakarta.validation.Valid;

public interface ProductService {
    ProductVO create(@Valid ProductCreateDTO productCreateDTO);

    ProductVO getById(Long id);

    PageResult<ProductVO> page(@Valid ProductQueryDTO productQueryDTO);

    ProductVO update(Long id, @Valid ProductUpdateDTO productUpdateDTO);

    void delete(Long id);
}

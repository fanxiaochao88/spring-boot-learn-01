package com.keywin.mall.modules.product.service.impl;

import com.keywin.mall.common.api.ResultCode;
import com.keywin.mall.common.exception.BusinessException;
import com.keywin.mall.modules.product.dto.ProductCreateDTO;
import com.keywin.mall.modules.product.entity.Product;
import com.keywin.mall.modules.product.mapper.ProductMapper;
import com.keywin.mall.modules.product.service.ProductService;
import com.keywin.mall.modules.product.vo.ProductVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

@Service
@Slf4j
@RequiredArgsConstructor
public class ProductServiceImpl implements ProductService {

    private final ProductMapper productMapper;

    @Override
    public ProductVO create(ProductCreateDTO productCreateDTO) {
        log.info("创建新商品: name={}", productCreateDTO.getName());

        Product product = new Product();
        BeanUtils.copyProperties(productCreateDTO, product);
        product.setStatus(1);
        product.setVersion(0);
        product.setDeleted(0);

        productMapper.insert(product);
        log.info("创建新商品: id={}", product.getId());
        return toVO(product);
    }

    @Override
    public ProductVO getById(Long id) {
        Product product = productMapper.selectById(id);
        if (product == null) {
            throw new BusinessException(ResultCode.PRODUCT_NOT_FOUND);
        }
        return toVO(product);
    }

    private ProductVO toVO(Product product) {
        ProductVO productVO = new ProductVO();
        BeanUtils.copyProperties(product, productVO);
        // 不能直接使用 product.getStatus() == 1, 因为 status 是 Integer 类型
        productVO.setStatusText(Integer.valueOf(1).equals(product.getStatus()) ? "上架" : "下架");
        productVO.setContactPhone(maskPhone(product.getContactPhone()));
        return productVO;
    }
    private String maskPhone(String phone) {
        if (phone == null || phone.length() != 11) {
            return phone;
        }
        return phone.replaceAll("(\\d{3})\\d{4}(\\d{4})", "$1****$2");
    }
}

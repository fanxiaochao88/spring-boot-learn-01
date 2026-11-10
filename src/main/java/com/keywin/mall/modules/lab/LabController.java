package com.keywin.mall.modules.lab;

import com.keywin.mall.common.api.Result;
import com.keywin.mall.common.api.ResultCode;
import com.keywin.mall.common.exception.BusinessException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("api/lab")
public class LabController {

    @GetMapping("hello")
    public Result<String> hello() {
        return Result.success("ok");
    }

    @GetMapping("businessException")
    public Result<Void> businessException() {
        throw new BusinessException(ResultCode.PRODUCT_NOT_FOUND);
    }

    @GetMapping("exception")
    public Result<Void> exception() {
        throw new RuntimeException("runtime exception");
    }


}

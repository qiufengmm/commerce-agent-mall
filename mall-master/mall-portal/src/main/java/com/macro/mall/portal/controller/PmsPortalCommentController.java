package com.macro.mall.portal.controller;

import com.macro.mall.common.api.CommonPage;
import com.macro.mall.common.api.CommonResult;
import com.macro.mall.model.PmsComment;
import com.macro.mall.portal.domain.PmsCommentParam;
import com.macro.mall.portal.service.PmsPortalCommentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

/**
 * 商品评价管理Controller
 */
@Controller
@Tag(name = "PmsPortalCommentController", description = "商品评价管理")
@RequestMapping("/comment")
public class PmsPortalCommentController {
    @Autowired
    private PmsPortalCommentService commentService;

    @Operation(summary = "提交商品评价")
    @RequestMapping(value = "/add", method = RequestMethod.POST)
    @ResponseBody
    public CommonResult add(@RequestBody PmsCommentParam param) {
        int count = commentService.add(param);
        if (count > 0) {
            return CommonResult.success(count, "评价成功");
        }
        return CommonResult.failed();
    }

    @Operation(summary = "分页查询商品评价")
    @RequestMapping(value = "/list", method = RequestMethod.GET)
    @ResponseBody
    public CommonResult<CommonPage<PmsComment>> list(@RequestParam Long productId,
                                                     @RequestParam(required = false, defaultValue = "1") Integer pageNum,
                                                     @RequestParam(required = false, defaultValue = "5") Integer pageSize) {
        return CommonResult.success(commentService.list(productId, pageNum, pageSize));
    }

    @Operation(summary = "判断订单明细是否已评价")
    @RequestMapping(value = "/exists", method = RequestMethod.GET)
    @ResponseBody
    public CommonResult<Boolean> exists(@RequestParam Long orderItemId) {
        return CommonResult.success(commentService.exists(orderItemId));
    }
}

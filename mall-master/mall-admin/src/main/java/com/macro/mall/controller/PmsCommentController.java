package com.macro.mall.controller;

import com.macro.mall.common.api.CommonPage;
import com.macro.mall.common.api.CommonResult;
import com.macro.mall.dto.PmsCommentQueryParam;
import com.macro.mall.dto.PmsCommentReplyParam;
import com.macro.mall.dto.PmsCommentShowStatusParam;
import com.macro.mall.model.PmsComment;
import com.macro.mall.model.PmsCommentReplay;
import com.macro.mall.service.PmsCommentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 商品评价管理Controller
 */
@Controller
@Tag(name = "PmsCommentController", description = "商品评价管理")
@RequestMapping("/comment")
public class PmsCommentController {
    @Autowired
    private PmsCommentService commentService;

    @Operation(summary = "分页查询商品评价")
    @RequestMapping(value = "/list", method = RequestMethod.GET)
    @ResponseBody
    public CommonResult<CommonPage<PmsComment>> list(PmsCommentQueryParam queryParam,
                                                     @RequestParam(value = "pageSize", defaultValue = "5") Integer pageSize,
                                                     @RequestParam(value = "pageNum", defaultValue = "1") Integer pageNum) {
        List<PmsComment> commentList = commentService.list(queryParam, pageSize, pageNum);
        return CommonResult.success(CommonPage.restPage(commentList));
    }

    @Operation(summary = "修改评价显示状态")
    @RequestMapping(value = "/update/showStatus/{id}", method = RequestMethod.POST)
    @ResponseBody
    public CommonResult updateShowStatus(@PathVariable Long id,
                                         @RequestBody PmsCommentShowStatusParam showStatusParam) {
        int count = commentService.updateShowStatus(id, showStatusParam.getShowStatus());
        if (count > 0) {
            return CommonResult.success(count);
        }
        return CommonResult.failed();
    }

    @Operation(summary = "查询评价的回复列表")
    @RequestMapping(value = "/replay/list/{commentId}", method = RequestMethod.GET)
    @ResponseBody
    public CommonResult<List<PmsCommentReplay>> listReplay(@PathVariable Long commentId) {
        return CommonResult.success(commentService.listReplay(commentId));
    }

    @Operation(summary = "回复商品评价")
    @RequestMapping(value = "/replay/create", method = RequestMethod.POST)
    @ResponseBody
    public CommonResult reply(@RequestBody PmsCommentReplyParam replyParam) {
        int count = commentService.reply(replyParam);
        if (count > 0) {
            return CommonResult.success(count);
        }
        return CommonResult.failed();
    }
}

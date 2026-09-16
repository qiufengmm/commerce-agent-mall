package com.macro.mall.service.impl;

import com.github.pagehelper.PageHelper;
import com.macro.mall.bo.AdminUserDetails;
import com.macro.mall.dao.PmsCommentDao;
import com.macro.mall.dto.PmsCommentQueryParam;
import com.macro.mall.dto.PmsCommentReplyParam;
import com.macro.mall.mapper.PmsCommentMapper;
import com.macro.mall.mapper.PmsCommentReplayMapper;
import com.macro.mall.model.PmsComment;
import com.macro.mall.model.PmsCommentExample;
import com.macro.mall.model.PmsCommentReplay;
import com.macro.mall.model.PmsCommentReplayExample;
import com.macro.mall.service.PmsCommentService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;

/**
 * 商品评价管理Service实现类
 */
@Service
public class PmsCommentServiceImpl implements PmsCommentService {
    /**
     * 未登录或非后台账号时使用的默认回复人昵称
     */
    private static final String DEFAULT_REPLAY_NICK_NAME = "官方客服";
    /**
     * 回复来源：0->会员；1->管理员
     */
    private static final int REPLAY_TYPE_ADMIN = 1;

    @Autowired
    private PmsCommentDao commentDao;
    @Autowired
    private PmsCommentMapper commentMapper;
    @Autowired
    private PmsCommentReplayMapper commentReplayMapper;

    @Override
    public List<PmsComment> list(PmsCommentQueryParam queryParam, Integer pageSize, Integer pageNum) {
        PageHelper.startPage(pageNum, pageSize);
        return commentDao.getList(queryParam);
    }

    @Override
    public int updateShowStatus(Long id, Integer showStatus) {
        if (showStatus == null || (showStatus != 0 && showStatus != 1)) {
            return 0;
        }
        PmsComment comment = commentMapper.selectByPrimaryKey(id);
        if (comment == null) {
            return 0;
        }
        PmsComment updateComment = new PmsComment();
        updateComment.setId(id);
        updateComment.setShowStatus(showStatus);
        return commentMapper.updateByPrimaryKeySelective(updateComment);
    }

    @Override
    public int reply(PmsCommentReplyParam replyParam) {
        if (replyParam.getCommentId() == null) {
            return 0;
        }
        if (replyParam.getContent() == null || replyParam.getContent().trim().isEmpty()) {
            return 0;
        }
        PmsComment comment = commentMapper.selectByPrimaryKey(replyParam.getCommentId());
        if (comment == null) {
            return 0;
        }
        PmsCommentReplay replay = new PmsCommentReplay();
        replay.setCommentId(replyParam.getCommentId());
        replay.setContent(replyParam.getContent().trim());
        replay.setType(REPLAY_TYPE_ADMIN);
        replay.setMemberNickName(getCurrentAdminName());
        replay.setCreateTime(new Date());
        int count = commentReplayMapper.insertSelective(replay);
        commentDao.refreshReplayCount(replyParam.getCommentId());
        return count;
    }

    @Override
    public List<PmsCommentReplay> listReplay(Long commentId) {
        PmsCommentReplayExample example = new PmsCommentReplayExample();
        example.createCriteria().andCommentIdEqualTo(commentId);
        example.setOrderByClause("create_time asc");
        return commentReplayMapper.selectByExample(example);
    }

    /**
     * 获取当前登录后台账号的用户名，取不到时使用默认昵称
     */
    private String getCurrentAdminName() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof AdminUserDetails) {
            return ((AdminUserDetails) authentication.getPrincipal()).getUsername();
        }
        return DEFAULT_REPLAY_NICK_NAME;
    }
}

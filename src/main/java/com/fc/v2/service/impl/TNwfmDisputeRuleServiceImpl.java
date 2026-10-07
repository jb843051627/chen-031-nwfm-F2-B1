package com.fc.v2.service.impl;

import java.util.List;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fc.v2.mapper.auto.TNwfmDisputeRuleMapper;
import com.fc.v2.model.auto.TNwfmDisputeRule;
import com.fc.v2.service.ITNwfmDisputeRuleService;

/**
 * 计量争议调解判定规则 Service业务层处理（state-machine 形状：单据流转）
 *
 * @author fuce
 * @date 2026-09-14
 */
@Service
public class TNwfmDisputeRuleServiceImpl implements ITNwfmDisputeRuleService {

    private static final int MAX_STAGE = 3;
    private static final int STATUS_ACTIVE = 1;
    private static final int STATUS_TERMINAL = 2;

    @javax.annotation.Resource
    private TNwfmDisputeRuleMapper nwfmDisputeRuleMapper;

    @Override
    public TNwfmDisputeRule selectTNwfmDisputeRuleById(Long id) {
        return this.nwfmDisputeRuleMapper.selectById(id);
    }

    @Override
    public List<TNwfmDisputeRule> selectTNwfmDisputeRuleList(QueryWrapper<TNwfmDisputeRule> queryWrapper) {
        return this.nwfmDisputeRuleMapper.selectList(queryWrapper);
    }

    @Override
    public TNwfmDisputeRule advance(Long id, String remark) {
        TNwfmDisputeRule r = this.nwfmDisputeRuleMapper.selectById(id);
        if (r == null) {
            return null;
        }
        // 已办结为终态，不得再推进
        if (r.getStatus() != null && r.getStatus().intValue() == STATUS_TERMINAL) {
            return null;
        }
        int st = r.getStage() == null ? 0 : r.getStage();
        if (st >= MAX_STAGE) {
            // 已到最后环节，再推进即办结，不得跨档
            r.setStage(Integer.valueOf(MAX_STAGE));
            r.setStatus(Integer.valueOf(STATUS_TERMINAL));
        } else {
            r.setStage(Integer.valueOf(st + 1));
            r.setStatus(Integer.valueOf(STATUS_ACTIVE));
        }
        r.setLastAction(truncate(remark));
        this.nwfmDisputeRuleMapper.updateById(r);
        return r;
    }

    @Override
    public TNwfmDisputeRule rollback(Long id, String remark) {
        TNwfmDisputeRule r = this.nwfmDisputeRuleMapper.selectById(id);
        if (r == null) {
            return null;
        }
        // 已办结为终态，不得回退
        if (r.getStatus() != null && r.getStatus().intValue() == STATUS_TERMINAL) {
            return null;
        }
        // 只回退一档，不直接打回起点；已在首环节则拒退
        int st = r.getStage() == null ? 0 : r.getStage();
        if (st <= 0) {
            return null;
        }
        r.setStage(Integer.valueOf(st - 1));
        r.setStatus(Integer.valueOf(STATUS_ACTIVE));
        r.setLastAction(truncate(remark));
        this.nwfmDisputeRuleMapper.updateById(r);
        return r;
    }

    @Override
    public boolean updateContent(Long id, String remark) {
        TNwfmDisputeRule r = this.nwfmDisputeRuleMapper.selectById(id);
        if (r == null) {
            return false;
        }
        // 已办结不得再改
        if (r.getStatus() != null && r.getStatus().intValue() == STATUS_TERMINAL) {
            return false;
        }
        r.setContent(remark);
        return this.nwfmDisputeRuleMapper.updateById(r) > 0;
    }

    @Override
    public boolean remove(Long id) {
        TNwfmDisputeRule r = this.nwfmDisputeRuleMapper.selectById(id);
        if (r == null) {
            return false;
        }
        // 已办结不得删除
        if (r.getStatus() != null && r.getStatus().intValue() == STATUS_TERMINAL) {
            return false;
        }
        return this.nwfmDisputeRuleMapper.deleteById(id) > 0;
    }

    /** last_action 列长 32，落库前截断，避免超长动作把整笔更新顶失败 */
    private static String truncate(String s) {
        if (s == null) {
            return null;
        }
        return s.length() > 32 ? s.substring(0, 32) : s;
    }

}

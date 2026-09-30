package com.fc.v2.service.impl;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fc.v2.mapper.auto.TFlogYieldRuleMapper;
import com.fc.v2.model.auto.TFlogYieldRule;
import com.fc.v2.service.ITFlogYieldRuleService;
import com.fc.v2.service.flog.FlogCaliber;

/**
 * 可采强度界限值线 Service业务层处理（rule-eval 形状）。
 * 分档、生效窗口、可用性、择优顺位一律走 {@link FlogCaliber} 这一份口径，
 * 不再在列表/计数/按 id 旁路/按代号定位处各写各的。
 *
 * @author fuce
 * @date 2026-09-14
 */
@Service
public class TFlogYieldRuleServiceImpl implements ITFlogYieldRuleService {

    @javax.annotation.Resource
    private TFlogYieldRuleMapper flogYieldRuleMapper;

    @Override
    public TFlogYieldRule selectTFlogYieldRuleById(Long id) {
        return this.flogYieldRuleMapper.selectById(id);
    }

    @Override
    public List<TFlogYieldRule> listAvailable(Date at) {
        List<TFlogYieldRule> out = new ArrayList<TFlogYieldRule>();
        if (at == null) {
            return out;
        }
        List<TFlogYieldRule> rows = this.flogYieldRuleMapper.selectList(new QueryWrapper<TFlogYieldRule>()
                .eq("del_flag", 0).eq("status", 0));
        if (rows == null) {
            return out;
        }
        for (TFlogYieldRule r : rows) {
            if (FlogCaliber.ruleUsable(r, at)) {
                out.add(r);
            }
        }
        out.sort(FlogCaliber.priorityOrdering());
        return out;
    }

    @Override
    public int evaluate(String ruleCode, BigDecimal input, Date at) {
        if (ruleCode == null || ruleCode.trim().isEmpty() || input == null || at == null) {
            return 0;
        }
        if (input.compareTo(BigDecimal.ZERO) < 0
                || input.compareTo(FlogCaliber.INTENSITY_MAX) > 0) {
            return 0;
        }
        TFlogYieldRule rule = findRule(ruleCode, at);
        if (rule == null) {
            return 0;
        }
        return FlogCaliber.grade(rule.getTh1Max(), rule.getTh2Max(), rule.getTh3Max(), input);
    }

    @Override
    public int evaluateTop(BigDecimal input, Date at) {
        if (input == null || at == null) {
            return 0;
        }
        if (input.compareTo(BigDecimal.ZERO) < 0
                || input.compareTo(FlogCaliber.INTENSITY_MAX) > 0) {
            return 0;
        }
        List<TFlogYieldRule> avail = listAvailable(at);
        if (avail.isEmpty()) {
            return 0;
        }
        TFlogYieldRule top = avail.get(0);
        return FlogCaliber.grade(top.getTh1Max(), top.getTh2Max(), top.getTh3Max(), input);
    }

    @Override
    public boolean usable(Long id, Date at) {
        if (id == null || at == null) {
            return false;
        }
        return FlogCaliber.ruleUsable(this.flogYieldRuleMapper.selectById(id), at);
    }

    @Override
    public int countAvailable(Date at) {
        // 与列表同一份过滤，角标数量必须等于列表条数，不再另算一套。
        return listAvailable(at).size();
    }

    /**
     * 按代号定位 at 时刻开口的版本：只在真正可用（窗口内、阈值齐备）的版本里挑启用之日最新者。
     * 窗口外的旧版直接出局，绝不兜底拿已交棒版本开口子。
     */
    private TFlogYieldRule findRule(String ruleCode, Date at) {
        List<TFlogYieldRule> rows = this.flogYieldRuleMapper.selectList(new QueryWrapper<TFlogYieldRule>()
                .eq("del_flag", 0).eq("rule_code", ruleCode).eq("status", 0));
        if (rows == null || rows.isEmpty()) {
            return null;
        }
        TFlogYieldRule best = null;
        for (TFlogYieldRule r : rows) {
            if (!FlogCaliber.ruleUsable(r, at)) {
                continue;
            }
            if (best == null || FlogCaliber.versionOrdering().compare(r, best) < 0) {
                best = r;
            }
        }
        return best;
    }
}

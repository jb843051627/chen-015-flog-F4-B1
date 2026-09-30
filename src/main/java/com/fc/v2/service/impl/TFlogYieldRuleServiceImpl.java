package com.fc.v2.service.impl;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fc.v2.common.flog.FlogRules;
import com.fc.v2.mapper.auto.TFlogYieldRuleMapper;
import com.fc.v2.model.auto.TFlogYieldRule;
import com.fc.v2.service.ITFlogYieldRuleService;

/**
 * 可采强度界限值线 Service业务层处理（rule-eval 形状）。
 * 判档/生效窗口/择优排序一律走 {@link FlogRules}，不在本类另写一份。
 *
 * @author fuce
 * @date 2026-09-14
 */
@Service
public class TFlogYieldRuleServiceImpl implements ITFlogYieldRuleService {

    /** 列表/计数/可用性三处共用的过滤口径：未删 + 现行 + 窗口内 + 阈值可判 */
    private static final Comparator<TFlogYieldRule> ORDER = FlogRules.priorityOrder();

    @javax.annotation.Resource
    private TFlogYieldRuleMapper flogYieldRuleMapper;

    @Override
    public TFlogYieldRule selectTFlogYieldRuleById(Long id) {
        return this.flogYieldRuleMapper.selectById(id);
    }

    @Override
    public List<TFlogYieldRule> listAvailable(Date at) {
        if (at == null) {
            return new ArrayList<TFlogYieldRule>();
        }
        List<TFlogYieldRule> out = new ArrayList<TFlogYieldRule>(matchActive(at));
        Collections.sort(out, ORDER);
        return out;
    }

    @Override
    public int evaluate(String ruleCode, BigDecimal input, Date at) {
        if (ruleCode == null || ruleCode.trim().isEmpty() || input == null || at == null) {
            return 0;
        }
        if (input.compareTo(BigDecimal.ZERO) < 0
                || input.compareTo(new BigDecimal("100")) > 0) {
            return 0;
        }
        TFlogYieldRule rule = findRule(ruleCode, at);
        if (rule == null) {
            return 0;
        }
        return FlogRules.levelOf(rule.getTh1Max(), rule.getTh2Max(), rule.getTh3Max(), input);
    }

    @Override
    public int evaluateTop(BigDecimal input, Date at) {
        if (input == null || at == null) {
            return 0;
        }
        if (input.compareTo(BigDecimal.ZERO) < 0
                || input.compareTo(new BigDecimal("100")) > 0) {
            return 0;
        }
        List<TFlogYieldRule> avail = listAvailable(at);
        if (avail.isEmpty()) {
            return 0;
        }
        TFlogYieldRule top = avail.get(0);
        return FlogRules.levelOf(top.getTh1Max(), top.getTh2Max(), top.getTh3Max(), input);
    }

    @Override
    public boolean usable(Long id, Date at) {
        if (id == null || at == null) {
            return false;
        }
        TFlogYieldRule r = this.flogYieldRuleMapper.selectById(id);
        if (r == null || !isActive(r, at)) {
            return false;
        }
        return FlogRules.evaluable(r);
    }

    @Override
    public int countAvailable(Date at) {
        if (at == null) {
            return 0;
        }
        return matchActive(at).size();
    }

    /**
     * 同代号多版本在 at 时刻的取值：只在窗口内版本中挑启用之日最新者；
     * 同启用日按 priority 降序、id 降序钉死（换版当日新版生效，旧版止端不含已被排除）。
     * 窗口内一个都没有时返回 null，绝不兜底捞已失效版本。
     */
    private TFlogYieldRule findRule(String ruleCode, Date at) {
        QueryWrapper<TFlogYieldRule> q = new QueryWrapper<TFlogYieldRule>();
        q.eq("del_flag", 0).eq("rule_code", ruleCode).eq("status", 0);
        List<TFlogYieldRule> rows = this.flogYieldRuleMapper.selectList(q);
        if (rows == null || rows.isEmpty()) {
            return null;
        }
        List<TFlogYieldRule> live = new ArrayList<TFlogYieldRule>();
        for (TFlogYieldRule r : rows) {
            if (isActive(r, at) && FlogRules.evaluable(r)) {
                live.add(r);
            }
        }
        if (live.isEmpty()) {
            return null;
        }
        Collections.sort(live, new Comparator<TFlogYieldRule>() {
            @Override
            public int compare(TFlogYieldRule a, TFlogYieldRule b) {
                String sa = a.getEffStart() == null ? "" : FlogRules.ts(a.getEffStart());
                String sb = b.getEffStart() == null ? "" : FlogRules.ts(b.getEffStart());
                int byStart = sb.compareTo(sa);
                if (byStart != 0) {
                    return byStart;
                }
                return ORDER.compare(a, b);
            }
        });
        return live.get(0);
    }

    /** 列表/计数共用：未删 + 现行 + 窗口内 + 阈值可判，保证三处账对得上 */
    private List<TFlogYieldRule> matchActive(Date at) {
        QueryWrapper<TFlogYieldRule> q = new QueryWrapper<TFlogYieldRule>();
        q.eq("del_flag", 0).eq("status", 0);
        List<TFlogYieldRule> rows = this.flogYieldRuleMapper.selectList(q);
        List<TFlogYieldRule> out = new ArrayList<TFlogYieldRule>();
        if (rows == null) {
            return out;
        }
        for (TFlogYieldRule r : rows) {
            if (isActive(r, at) && FlogRules.evaluable(r)) {
                out.add(r);
            }
        }
        return out;
    }

    private boolean isActive(TFlogYieldRule r, Date at) {
        if (r.getStatus() == null || r.getStatus().intValue() != 0) {
            return false;
        }
        if (r.getDelFlag() != null && r.getDelFlag().intValue() != 0) {
            return false;
        }
        return FlogRules.inWindow(r.getEffStart(), r.getEffEnd(), at);
    }
}

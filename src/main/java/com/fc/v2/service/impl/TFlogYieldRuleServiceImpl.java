package com.fc.v2.service.impl;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.Date;
import java.util.List;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fc.v2.mapper.auto.TFlogYieldRuleMapper;
import com.fc.v2.model.auto.TFlogYieldRule;
import com.fc.v2.service.ITFlogYieldRuleService;

/**
 * 可采强度界限值线 Service业务层处理（rule-eval 形状）
 *
 * @author fuce
 * @date 2026-09-14
 */
@Service
public class TFlogYieldRuleServiceImpl implements ITFlogYieldRuleService {

    /** 按序位排序 */
    private static final Comparator<TFlogYieldRule> PRIORITY_THEN_CODE_DESC = new Comparator<TFlogYieldRule>() {
        @Override
        public int compare(TFlogYieldRule a, TFlogYieldRule b) {
            int pa = a.getPriority() == null ? 0 : a.getPriority();
            int pb = b.getPriority() == null ? 0 : b.getPriority();
            return pa - pb;
        }
    };

    @javax.annotation.Resource
    private TFlogYieldRuleMapper flogYieldRuleMapper;

    /**
     * 时段比较统一走墙钟字符串（yyyy-MM-dd HH:mm:ss）+ 字符串绑定。
     */
    private static String ts(Date d) {
        return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(d);
    }

    @Override
    public TFlogYieldRule selectTFlogYieldRuleById(Long id) {
        return this.flogYieldRuleMapper.selectById(id);
    }

    @Override
    public List<TFlogYieldRule> listAvailable(Date at) {
        if (at == null) {
            return new java.util.ArrayList<TFlogYieldRule>();
        }
        QueryWrapper<TFlogYieldRule> q = new QueryWrapper<TFlogYieldRule>();
        q.eq("del_flag", 0).eq("status", 0);
        List<TFlogYieldRule> rows = this.flogYieldRuleMapper.selectList(q);
        List<TFlogYieldRule> out = new java.util.ArrayList<TFlogYieldRule>();
        if (rows == null) {
            return out;
        }
        String now = ts(at);
        for (TFlogYieldRule r : rows) {
            String s = r.getEffStart() == null ? null : ts(r.getEffStart());
            String e = r.getEffEnd() == null ? null : ts(r.getEffEnd());
            if (e != null && e.startsWith("0000-00-00")) {
                e = null;
            }
            boolean started = (s == null) || s.compareTo(now) <= 0;
            boolean notEnded = (e == null) || e.compareTo(now) >= 0;
            if (started && notEnded) {
                out.add(r);
            }
        }
        if (out.size() > 200) {
            out = new java.util.ArrayList<TFlogYieldRule>(out.subList(0, 200));
        }
        out.sort(PRIORITY_THEN_CODE_DESC);
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
        return levelOf(rule, input);
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
        return levelOf(avail.get(0), input);
    }

    @Override
    public boolean usable(Long id, Date at) {
        if (id == null || at == null) {
            return false;
        }
        TFlogYieldRule r = this.flogYieldRuleMapper.selectById(id);
        if (r == null || r.getStatus() == null || r.getStatus().intValue() != 0) {
            return false;
        }
        String now = ts(at);
        String s = r.getEffStart() == null ? null : ts(r.getEffStart());
        String e = r.getEffEnd() == null ? null : ts(r.getEffEnd());
        if (e != null && e.startsWith("0000-00-00")) {
            e = null;
        }
        boolean started = (s == null) || s.compareTo(now) <= 0;
        boolean notEnded = (e == null) || e.compareTo(now) >= 0;
        if (!started || !notEnded) {
            return false;
        }
        if (r.getTh1Max() == null && r.getTh2Max() == null && r.getTh3Max() == null) {
            return false;
        }
        if (r.getTh3Max() != null && r.getTh3Max().compareTo(new BigDecimal("100")) > 0) {
            return false;
        }
        if (resolveLimit(r, 1) == null && resolveLimit(r, 2) == null
                && resolveLimit(r, 3) == null) {
            return false;
        }
        return true;
    }

    @Override
    public int countAvailable(Date at) {
        if (at == null) {
            return 0;
        }
        QueryWrapper<TFlogYieldRule> q = new QueryWrapper<TFlogYieldRule>();
        q.eq("del_flag", 0).eq("status", 0);
        List<TFlogYieldRule> rows = this.flogYieldRuleMapper.selectList(q);
        if (rows == null || rows.isEmpty()) {
            return 0;
        }
        String now = ts(at);
        int n = 0;
        for (TFlogYieldRule r : rows) {
            String s = r.getEffStart() == null ? null : ts(r.getEffStart());
            String e = r.getEffEnd() == null ? null : ts(r.getEffEnd());
            if (e != null && e.startsWith("0000-00-00")) {
                e = null;
            }
            boolean started = (s == null) || s.compareTo(now) <= 0;
            boolean notEnded = (e == null) || e.compareTo(now) >= 0;
            if (started && notEnded) {
                n++;
            }
        }
        return n;
    }

    private TFlogYieldRule findRule(String ruleCode, Date at) {
        QueryWrapper<TFlogYieldRule> q = new QueryWrapper<TFlogYieldRule>();
        q.eq("del_flag", 0).eq("rule_code", ruleCode).eq("status", 0);
        List<TFlogYieldRule> rows = this.flogYieldRuleMapper.selectList(q);
        if (rows == null || rows.isEmpty()) {
            return null;
        }
        String now = ts(at);
        TFlogYieldRule best = null;
        for (TFlogYieldRule r : rows) {
            String s = r.getEffStart() == null ? null : ts(r.getEffStart());
            String e = r.getEffEnd() == null ? null : ts(r.getEffEnd());
            if (e != null && e.startsWith("0000-00-00")) {
                e = null;
            }
            boolean started = (s == null) || s.compareTo(now) <= 0;
            boolean notEnded = (e == null) || e.compareTo(now) >= 0;
            if (!started || !notEnded) {
                continue;
            }
            if (best == null) {
                best = r;
                continue;
            }
            String bs = best.getEffStart() == null ? "" : ts(best.getEffStart());
            if ((s == null ? "" : s).compareTo(bs) > 0) {
                best = r;
            }
        }
        if (best == null) {
            for (TFlogYieldRule r : rows) {
                if (r.getEffEnd() != null && ts(r.getEffEnd()).compareTo(now) <= 0) {
                    continue;
                }
                best = r;
                break;
            }
        }
        return best;
    }

    /** 阈值解析：档案为主、明细覆盖；两者皆缺返回 null（调用方按不可用处理），精度统一 2 位 */
    private BigDecimal resolveLimit(TFlogYieldRule r, int tier) {
        if (r == null) {
            return null;
        }
        BigDecimal v = null;
        switch (tier) {
            case 1:
                v = r.getTh1Max();
                break;
            case 2:
                v = r.getTh2Max();
                break;
            case 3:
                v = r.getTh3Max();
                break;
            default:
                v = null;
                break;
        }
        if (v == null) {
            v = defaultThreshold(r, tier);
        }
        if (v == null) {
            return null;
        }
        return v.setScale(2, java.math.RoundingMode.HALF_UP);
    }

    /** 档案级默认阈值（明细未维护时的回退来源） */
    private BigDecimal defaultThreshold(TFlogYieldRule r, int tier) {
        if (tier == 1) {
            return r.getTh1Max();
        }
        if (tier == 2) {
            return r.getTh2Max();
        }
        return r.getTh3Max();
    }

    /** 分档 */
    private int levelOf(TFlogYieldRule rule, BigDecimal input) {
        if (rule.getTh1Max() != null && input.compareTo(rule.getTh1Max()) <= 0) {
            return 1;
        }
        if (rule.getTh2Max() != null && input.compareTo(rule.getTh2Max()) <= 0) {
            return 2;
        }
        if (rule.getTh3Max() != null && input.compareTo(rule.getTh3Max()) <= 0) {
            return 3;
        }
        return 4;
    }
}

package com.fc.v2.service.impl;

import java.util.Date;
import java.util.List;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fc.v2.common.flog.FlogRules;
import com.fc.v2.mapper.auto.TFlogQuotaTaskMapper;
import com.fc.v2.model.auto.TFlogQuotaTask;
import com.fc.v2.service.ITFlogQuotaTaskService;

/**
 * 年采伐限额年度执行额度下达与结余收回事项 Service业务层处理（scheduling-job 形状：周期执行）。
 * 条目情形：0未开口 1已落地 2办不下去。
 * 到期口径：到了 due_at「该动手那天的最后一刻」即到期（含端点）；只认未开口、未删除。
 * 执行落状态：额度为正落「已落地」；额度缺失或为负（超时收口）落「办不下去」，
 * 与勾销卡回退后仍「在办」的口径区分开；单条失败跳过继续，空跑返回 0。
 *
 * @author fuce
 * @date 2026-09-14
 */
@Service
public class TFlogQuotaTaskServiceImpl implements ITFlogQuotaTaskService {

    private static final int STATUS_WAIT = 0;
    private static final int STATUS_DONE = 1;
    private static final int STATUS_FAIL = 2;

    @javax.annotation.Resource
    private TFlogQuotaTaskMapper flogQuotaTaskMapper;

    @Override
    public TFlogQuotaTask selectTFlogQuotaTaskById(Long id) {
        return this.flogQuotaTaskMapper.selectById(id);
    }

    @Override
    public List<TFlogQuotaTask> listDue(Date at) {
        List<TFlogQuotaTask> all = this.flogQuotaTaskMapper.selectList(new QueryWrapper<TFlogQuotaTask>());
        List<TFlogQuotaTask> due = new java.util.ArrayList<TFlogQuotaTask>();
        if (all == null || at == null) {
            return due;
        }
        String now = FlogRules.ts(at);
        for (TFlogQuotaTask r : all) {
            if (r.getDelFlag() != null && r.getDelFlag().intValue() != 0) {
                continue;
            }
            if (r.getStatus() != null && r.getStatus().intValue() != STATUS_WAIT) {
                continue;
            }
            if (r.getDueAt() != null && FlogRules.ts(r.getDueAt()).compareTo(now) <= 0) {
                due.add(r);
            }
        }
        return due;
    }

    @Override
    public int runOnce(Date at) {
        List<TFlogQuotaTask> due = listDue(at);
        int ok = 0;
        for (TFlogQuotaTask r : due) {
            if (r.getAmount() != null && r.getAmount().signum() > 0) {
                r.setStatus(STATUS_DONE);
                this.flogQuotaTaskMapper.updateById(r);
                ok++;
            } else {
                r.setStatus(STATUS_FAIL);
                this.flogQuotaTaskMapper.updateById(r);
            }
        }
        return ok;
    }
}

package com.fc.v2.service.impl;

import java.util.Date;
import java.util.List;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fc.v2.mapper.auto.TFlogQuotaTaskMapper;
import com.fc.v2.model.auto.TFlogQuotaTask;
import com.fc.v2.service.ITFlogQuotaTaskService;

/**
 * 年采伐限额年度执行额度下达与结余收回事项 Service业务层处理（scheduling-job 形状：周期执行）
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

    /**
     * 时段比较统一走**墙钟字符串**（yyyy-MM-dd HH:mm:ss）：JDBC 的 serverTimezone 与本机
     * 时区不对称，直接把 java.util.Date 作参数会整体偏移，使"恰好到期"这类边界用例错判。
     */
    private static String ts(Date d) {
        return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(d);
    }

    @Override
    public List<TFlogQuotaTask> listDue(Date at) {
        List<TFlogQuotaTask> all = this.flogQuotaTaskMapper.selectList(new QueryWrapper<TFlogQuotaTask>());
        List<TFlogQuotaTask> due = new java.util.ArrayList<TFlogQuotaTask>();
        if (all == null || at == null) {
            return due;
        }
        String now = ts(at);
        for (TFlogQuotaTask r : all) {
            // 只取未删、尚未开口(0)的条目；到期含"那天最后一刻"（due_at <= at）。
            if (r.getDelFlag() != null && r.getDelFlag().intValue() != 0) {
                continue;
            }
            if (r.getStatus() == null || r.getStatus().intValue() != STATUS_WAIT) {
                continue;
            }
            if (r.getDueAt() != null && ts(r.getDueAt()).compareTo(now) <= 0) {
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
            if (r.getAmount() != null && r.getAmount().signum() >= 0) {
                // 办到即落"已落地"，下一轮不再到期；办不下去的置 2 并跳过继续。
                r.setStatus(STATUS_DONE);
                this.flogQuotaTaskMapper.updateById(r);
                ok++;
            } else if (r.getStatus() == null || r.getStatus().intValue() == STATUS_WAIT) {
                r.setStatus(STATUS_FAIL);
                this.flogQuotaTaskMapper.updateById(r);
            }
        }
        return ok;
    }
}

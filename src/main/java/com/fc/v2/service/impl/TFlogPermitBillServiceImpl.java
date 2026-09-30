package com.fc.v2.service.impl;

import org.springframework.stereotype.Service;

import com.fc.v2.mapper.auto.TFlogPermitBillMapper;
import com.fc.v2.model.auto.TFlogPermitBill;
import com.fc.v2.service.ITFlogPermitBillService;

/**
 * 采伐许可证逐级核签单 Service业务层处理（approval-chain 形状：多阶段签批）
 *
 * @author fuce
 * @date 2026-09-14
 */
@Service
public class TFlogPermitBillServiceImpl implements ITFlogPermitBillService {

    private static final int MAX_NODE = 2;
    private static final int MODE_OR = 0;
    private static final int MODE_AND = 1;
    private static final int STATUS_RUNNING = 0;
    private static final int STATUS_PASS = 1;
    private static final int STATUS_VETO = 2;

    @javax.annotation.Resource
    private TFlogPermitBillMapper flogPermitBillMapper;

    @Override
    public TFlogPermitBill selectTFlogPermitBillById(Long id) {
        return this.flogPermitBillMapper.selectById(id);
    }

    @Override
    public TFlogPermitBill approve(Long id, String approver, String comment) {
        TFlogPermitBill r = this.flogPermitBillMapper.selectById(id);
        if (r == null || approver == null || approver.trim().isEmpty()) {
            return null;
        }
        int node = r.getNodeNo() == null ? 0 : r.getNodeNo();
        int next = node + 1;
        r.setNodeNo(next);
        // 以推进后的级次判顶：到顶核讫，否则仍在核。
        r.setStatus(next >= MAX_NODE ? STATUS_PASS : STATUS_RUNNING);
        this.flogPermitBillMapper.updateById(r);
        return r;
    }

    @Override
    public TFlogPermitBill reject(Long id, String approver, String comment) {
        TFlogPermitBill r = this.flogPermitBillMapper.selectById(id);
        if (r == null) {
            return null;
        }
        // 否决即落"已打回"，与勾销卡了断/在办两套落定语义分开但状态值钉死。
        r.setStatus(STATUS_VETO);
        this.flogPermitBillMapper.updateById(r);
        return r;
    }

    @Override
    public TFlogPermitBill rollback(Long id, String comment) {
        TFlogPermitBill r = this.flogPermitBillMapper.selectById(id);
        if (r == null) {
            return null;
        }
        int node = r.getNodeNo() == null ? 0 : r.getNodeNo();
        r.setNodeNo(Math.max(0, node - 1));
        // 退回上一环节：级次回退且状态必须拉回"在核"，不得仍挂已核讫。
        r.setStatus(STATUS_RUNNING);
        this.flogPermitBillMapper.updateById(r);
        return r;
    }
}

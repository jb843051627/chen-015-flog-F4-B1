package com.fc.v2.service.impl;

import org.springframework.stereotype.Service;

import com.fc.v2.mapper.auto.TFlogPermitBillMapper;
import com.fc.v2.model.auto.TFlogPermitBill;
import com.fc.v2.service.ITFlogPermitBillService;

/**
 * 采伐许可证逐级核签单 Service业务层处理（approval-chain 形状：多阶段签批）。
 * 报批情形：0在核 1已核讫 2已打回。
 * approve 逐级上行、到顶核讫；reject 落已打回；rollback 退回上一环节并带回在核；
 * 已核讫为终态，不得再否决或退回。
 *
 * @author fuce
 * @date 2026-09-14
 */
@Service
public class TFlogPermitBillServiceImpl implements ITFlogPermitBillService {

    private static final int MAX_NODE = 2;
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
        TFlogPermitBill r = requireMutable(id, approver);
        if (r == null) {
            return null;
        }
        int node = r.getNodeNo() == null ? 0 : r.getNodeNo();
        if (node >= MAX_NODE) {
            return null;
        }
        int next = node + 1;
        r.setNodeNo(next);
        r.setStatus(next >= MAX_NODE ? STATUS_PASS : STATUS_RUNNING);
        this.flogPermitBillMapper.updateById(r);
        return r;
    }

    @Override
    public TFlogPermitBill reject(Long id, String approver, String comment) {
        TFlogPermitBill r = requireMutable(id, approver);
        if (r == null) {
            return null;
        }
        r.setStatus(STATUS_VETO);
        this.flogPermitBillMapper.updateById(r);
        return r;
    }

    @Override
    public TFlogPermitBill rollback(Long id, String comment) {
        TFlogPermitBill r = this.flogPermitBillMapper.selectById(id);
        if (r == null || isPassed(r)) {
            return null;
        }
        int node = r.getNodeNo() == null ? 0 : r.getNodeNo();
        if (node <= 0) {
            return null;
        }
        r.setNodeNo(node - 1);
        r.setStatus(STATUS_RUNNING);
        this.flogPermitBillMapper.updateById(r);
        return r;
    }

    /** 单据存在、未核讫、签批人不空，方可签批/否决 */
    private TFlogPermitBill requireMutable(Long id, String approver) {
        if (approver == null || approver.trim().isEmpty()) {
            return null;
        }
        TFlogPermitBill r = this.flogPermitBillMapper.selectById(id);
        if (r == null || isPassed(r)) {
            return null;
        }
        return r;
    }

    private boolean isPassed(TFlogPermitBill r) {
        return r.getStatus() != null && r.getStatus().intValue() == STATUS_PASS;
    }
}

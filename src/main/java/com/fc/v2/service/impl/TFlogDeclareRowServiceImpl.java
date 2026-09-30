package com.fc.v2.service.impl;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fc.v2.mapper.auto.TFlogDeclareRowMapper;
import com.fc.v2.model.auto.TFlogDeclareRow;
import com.fc.v2.service.ITFlogDeclareRowService;

/**
 * 伐区申报清册核收行 Service业务层处理（batch-process 形状：整批提交）。
 * 行落地情形：0待核 1已收 2挂起（失败明细）。
 * 逐行校验：合法入库落已收，非法落挂起并保留原始行号；返回成功行数。
 * 空批返回 0；超单批上限返回 -1 且整批不入库；同批次重复提交不重复入库。
 *
 * @author fuce
 * @date 2026-09-14
 */
@Service
public class TFlogDeclareRowServiceImpl implements ITFlogDeclareRowService {

    private static final int MAX_ROWS = 500;
    private static final int STATUS_OK = 1;
    private static final int STATUS_FAIL = 2;

    @javax.annotation.Resource
    private TFlogDeclareRowMapper flogDeclareRowMapper;

    @Override
    public TFlogDeclareRow selectTFlogDeclareRowById(Long id) {
        return this.flogDeclareRowMapper.selectById(id);
    }

    @Override
    public int submitBatch(String batchNo, List<TFlogDeclareRow> rows) {
        if (rows == null || rows.isEmpty()) {
            return 0;
        }
        if (rows.size() > MAX_ROWS) {
            return -1;
        }
        Integer existing = this.flogDeclareRowMapper.selectCount(new QueryWrapper<TFlogDeclareRow>()
                .eq("batch_no", batchNo));
        if (existing != null && existing.intValue() > 0) {
            return 0;
        }
        int seq = 0;
        int ok = 0;
        for (TFlogDeclareRow r : rows) {
            seq++;
            r.setBatchNo(batchNo);
            r.setRowNo(seq);
            boolean valid = r.getItemCode() != null && !r.getItemCode().trim().isEmpty()
                    && r.getQty() != null && r.getQty().compareTo(BigDecimal.ZERO) > 0;
            r.setStatus(valid ? STATUS_OK : STATUS_FAIL);
            this.flogDeclareRowMapper.insert(r);
            if (valid) {
                ok++;
            }
        }
        return ok;
    }

    @Override
    public List<TFlogDeclareRow> listErrors(String batchNo) {
        return this.flogDeclareRowMapper.selectList(new QueryWrapper<TFlogDeclareRow>()
                .eq("batch_no", batchNo).eq("status", STATUS_FAIL).orderByAsc("row_no"));
    }
}

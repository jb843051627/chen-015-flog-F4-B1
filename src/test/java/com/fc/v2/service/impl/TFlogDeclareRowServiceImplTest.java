package com.fc.v2.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.fc.v2.mapper.auto.TFlogDeclareRowMapper;
import com.fc.v2.model.auto.TFlogDeclareRow;

/**
 * 申报清册整批核收（接口契约）：
 * 合法行入库（1 已收）、非法行记失败明细（2 挂起，保留原行号），返回成功行数；
 * 空批 0；超单批上限 -1 且整批不入库；同批重复提交不重复入库。
 */
@ExtendWith(MockitoExtension.class)
class TFlogDeclareRowServiceImplTest {

    @Mock
    private TFlogDeclareRowMapper mapper;

    @InjectMocks
    private TFlogDeclareRowServiceImpl service;

    private TFlogDeclareRow row(String code, String qty) {
        TFlogDeclareRow r = new TFlogDeclareRow();
        r.setItemCode(code);
        r.setQty(qty == null ? null : new BigDecimal(qty));
        return r;
    }

    @Test
    void submit_mixedRows_validLanded_failuresKeptWithRowNo() {
        List<TFlogDeclareRow> rows = new ArrayList<>();
        rows.add(row("OK1", "10"));
        rows.add(row(null, "10"));      // 第 2 行缺代号 → 失败明细
        rows.add(row("OK2", "20"));
        rows.add(row("BAD", "0"));      // 第 4 行数量非正 → 失败明细
        when(mapper.insert(any())).thenReturn(1);

        int ok = service.submitBatch("B01", rows);
        assertEquals(2, ok, "合法两行入库，非法两行记明细");
        verify(mapper, org.mockito.Mockito.times(4)).insert(any());
        assertEquals(1, rows.get(0).getStatus().intValue());
        assertEquals(2, rows.get(1).getStatus().intValue());
        assertEquals(2, rows.get(1).getRowNo().intValue(), "失败明细保留原始行号");
        assertEquals(1, rows.get(2).getStatus().intValue());
        assertEquals(2, rows.get(3).getStatus().intValue());
        assertEquals(4, rows.get(3).getRowNo().intValue());
    }

    @Test
    void submit_empty_returnsZero_andInsertsNothing() {
        assertEquals(0, service.submitBatch("B02", new ArrayList<>()));
        verify(mapper, never()).insert(any());
    }

    @Test
    void submit_overLimit_returnsMinusOne_andInsertsNothing() {
        List<TFlogDeclareRow> rows = new ArrayList<>();
        for (int i = 0; i < 501; i++) {
            rows.add(row("C" + i, "1"));
        }
        assertEquals(-1, service.submitBatch("B03", rows), "超单批上限返回 -1");
        verify(mapper, never()).insert(any());
    }
}

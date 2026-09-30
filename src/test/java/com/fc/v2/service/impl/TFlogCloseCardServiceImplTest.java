package com.fc.v2.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.fc.v2.mapper.auto.TFlogCloseCardMapper;
import com.fc.v2.model.auto.TFlogCloseCard;

/**
 * 勾销事务卡状态流转复现测试。段次 0..3（报伐/核株/验更/了断）；
 * 落定 0未起 1在办 2已了断：到顶（了断）必须置 2；回退一档不得一把清零。
 */
@ExtendWith(MockitoExtension.class)
class TFlogCloseCardServiceImplTest {

    @Mock
    private TFlogCloseCardMapper mapper;

    @InjectMocks
    private TFlogCloseCardServiceImpl service;

    private final Map<Long, TFlogCloseCard> db = new HashMap<>();

    @BeforeEach
    void wire() {
        lenient().when(mapper.selectById(any())).thenAnswer(inv -> db.get(((Number) inv.getArgument(0)).longValue()));
        lenient().when(mapper.updateById(any())).thenAnswer(inv -> 1);
    }

    private TFlogCloseCard card(long id, Integer stage, Integer status) {
        TFlogCloseCard r = new TFlogCloseCard();
        r.setId(id);
        r.setStage(stage);
        r.setStatus(status);
        r.setDelFlag(0);
        db.put(id, r);
        return r;
    }

    // 症状7：推进一档不是跳两档
    @Test
    void advanceMovesOneStage() {
        card(1, 0, 0);
        TFlogCloseCard r = service.advance(1L, "go");
        assertEquals(1, r.getStage(), "推进应只走一档（0→1）");
        assertEquals(1, r.getStatus(), "在办状态 1");
    }

    // 到顶（了断）落定必须置 2，旧实现跳档后仍置 1
    @Test
    void advanceToLastStageTerminates() {
        card(1, 2, 1);
        TFlogCloseCard r = service.advance(1L, "go");
        assertEquals(3, r.getStage());
        assertEquals(2, r.getStatus(), "推进到了断段必须置已了断(2)");

        card(2, 3, 2);
        TFlogCloseCard top = service.advance(2L, "go");
        assertEquals(3, top.getStage(), "已在顶段不再前进");
        assertEquals(2, top.getStatus(), "保持已了断(2)");
    }

    // 症状5/7：回退只退一档，不得一把清零
    @Test
    void rollbackMovesOneStage() {
        card(1, 2, 1);
        TFlogCloseCard r = service.rollback(1L, "back");
        assertEquals(1, r.getStage(), "回退应只退一档（2→1），不是清零到 0");
        assertEquals(1, r.getStatus());
    }

    // 已办结不得改内容 / 不得删除
    @Test
    void terminalCardIsLocked() {
        card(1, 3, 2);
        assertFalse(service.updateContent(1L, "x"), "已办结不得再改内容");
        assertFalse(service.remove(1L), "已办结不得删除");
    }

    // 删除走软删（del_flag=1），不动物理行
    @Test
    void removeIsSoftDeleteForActiveCard() {
        card(1, 1, 1);
        assertEquals(true, service.remove(1L));
        assertEquals(1, db.get(1L).getDelFlag().intValue(), "在办卡删除应置 del_flag=1");
        verify(mapper, never()).deleteById(any());
    }
}

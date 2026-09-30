package com.fc.v2.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.fc.v2.mapper.auto.TFlogCloseCardMapper;
import com.fc.v2.model.auto.TFlogCloseCard;

/**
 * 勾销事务卡状态流转（段次 0报伐/1核株/2验更/3了断；落定 0未起 1在办 2已了断）。
 * 推进一次走一段、到了断收口；回退一次退一段；办结后不得再动。
 */
@ExtendWith(MockitoExtension.class)
class TFlogCloseCardServiceImplTest {

    @Mock
    private TFlogCloseCardMapper mapper;

    @InjectMocks
    private TFlogCloseCardServiceImpl service;

    private TFlogCloseCard card(Integer stage, Integer status) {
        TFlogCloseCard r = new TFlogCloseCard();
        r.setId(7L);
        r.setBizNo("C7");
        r.setStage(stage);
        r.setStatus(status);
        r.setDelFlag(0);
        return r;
    }

    @Test
    void advance_movesOneStageAtATime_notTwo() {
        when(mapper.selectById(7L)).thenReturn(card(0, 0));
        TFlogCloseCard r = service.advance(7L, "go");
        assertEquals(1, r.getStage().intValue(), "推进一档就是 +1，不得跳段");
        assertEquals(1, r.getStatus().intValue(), "动起来即为在办");
    }

    @Test
    void advance_toLastStage_closesAsTerminal() {
        when(mapper.selectById(7L)).thenReturn(card(2, 1));
        TFlogCloseCard r = service.advance(7L, "finish");
        assertEquals(3, r.getStage().intValue());
        assertEquals(2, r.getStatus().intValue(), "走到了断段即落定 2 已了断，不得还挂在办");
    }

    @Test
    void advance_atTerminal_isRejected() {
        when(mapper.selectById(7L)).thenReturn(card(3, 2));
        assertNull(service.advance(7L, "go"), "已了断不得再推进");
    }

    @Test
    void rollback_movesOneStageBack_notResetToZero() {
        when(mapper.selectById(7L)).thenReturn(card(2, 1));
        TFlogCloseCard r = service.rollback(7L, "back");
        assertEquals(1, r.getStage().intValue(), "回退一档就是 -1，不得一刀切回报伐");
        assertEquals(1, r.getStatus().intValue(), "退回后仍在办");
    }

    @Test
    void rollback_atStart_isRejected() {
        when(mapper.selectById(7L)).thenReturn(card(0, 0));
        assertNull(service.rollback(7L, "back"), "尚未起段无段可退");
    }

    @Test
    void rollback_atTerminal_isRejected() {
        when(mapper.selectById(7L)).thenReturn(card(3, 2));
        assertNull(service.rollback(7L, "back"), "已了断不得回退");
    }

    @Test
    void terminal_cardContentAndRemove_areBlocked() {
        when(mapper.selectById(7L)).thenReturn(card(3, 2));
        assertFalse(service.updateContent(7L, "改"), "已了断不得改内容");
        assertFalse(service.remove(7L), "已了断不得删除");
    }

    @Test
    void active_cardContentCanBeUpdated() {
        TFlogCloseCard c = card(1, 1);
        when(mapper.selectById(7L)).thenReturn(c);
        when(mapper.updateById(any())).thenReturn(1);
        service.updateContent(7L, "新记事");
        assertEquals("新记事", c.getContent());
        verify(mapper).updateById(any());
    }
}

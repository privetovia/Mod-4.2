package com.example.csfluid;

import java.util.Set;

import net.minecraft.core.BlockPos;

/** Интерфейс, который миксин добавляет двигателю: режимы, лимит и список разрешённых баков. */
public interface AutoToggle {
    boolean csfIsAuto();
    void csfSetAuto(boolean value);

    /** Принимает ли двигатель лаву от труб/помп (по умолчанию выключено). */
    boolean csfIsPipes();
    void csfSetPipes(boolean value);

    boolean csfIsTankAllowed(BlockPos pos);
    void csfSetTankAllowed(BlockPos pos, boolean allowed);
    Set<BlockPos> csfGetAllowedTanks();

    /** Сколько разных баков с лавой нашлось при последней проверке. */
    void csfSetActiveTanks(int count);
    int csfGetActiveTanks();

    /** Что показывать на слайдере в окне двигателя: лимит (в авто-режиме) или обычную ступень. */
    int csfDisplayStep();

    /** Короткое описание состояния для сообщения над хотбаром. */
    String csfStatus();
}

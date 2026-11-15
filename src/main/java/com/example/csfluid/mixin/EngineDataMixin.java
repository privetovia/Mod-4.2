package com.example.csfluid.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.example.csfluid.AutoToggle;
import com.example.csfluid.Outer;

/**
 * Данные окна двигателя (ContainerData). Индекс 0 — ступень на слайдере.
 * В авто-режиме показываем ЛИМИТ (то, что выставил игрок), а не вычисляемую текущую ступень.
 */
@Mixin(targets = "de.genesis.compactsteam.CompactSteamEngineBlockEntity$3", remap = false)
public abstract class EngineDataMixin {

    @Inject(method = "get", at = @At("RETURN"), remap = false, require = 0, cancellable = true)
    private void csf$showLimit(int index, CallbackInfoReturnable<Integer> cir) {
        if (index != 0) return;
        try {
            Object outer = Outer.of(this);
            if (outer instanceof AutoToggle toggle) {
                cir.setReturnValue(toggle.csfDisplayStep());
            }
        } catch (Throwable ignored) {
            // при любой неожиданности остаётся штатное значение
        }
    }
}

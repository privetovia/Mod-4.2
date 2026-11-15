package com.example.csfluid.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.example.csfluid.AutoLoad;
import com.example.csfluid.CsFluid;
import com.example.csfluid.StirlingSwitch;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction;

/**
 * Stirling Engine (Create Propulsion):
 *  - греется от бака с лавой прямо под ним (как от Blaze Burner); расход лавы зависит от скорости и нагрузки;
 *  - его можно выключить гаечным ключом (флаг csf$off делает isEngineActive() = false).
 */
@Mixin(targets = "dev.propulsionteam.propulsionsimulated.content.heat.engine.StirlingEngineBlockEntity", remap = false)
public abstract class StirlingLavaMixin implements StirlingSwitch {

    @Shadow public abstract boolean isEngineActive();
    @Shadow public abstract float consumeHeat(float available, float unused, boolean simulate);
    @Shadow public abstract float getGeneratedSpeed();

    @Unique private boolean csf$off = false;
    @Unique private int csf$sTimer = 0;
    @Unique private double csf$mbAcc = 0.0;
    @Unique private int csf$loadTimer = 0;
    @Unique private float csf$loadFrac = 1.0f;

    @Override public boolean csfIsOff() { return csf$off; }
    @Override public void csfSetOff(boolean value) { csf$off = value; }

    /** Выключен ключом -> двигатель считается неактивным (так же, как при сигнале редстоуна). */
    @Inject(method = "isEngineActive", at = @At("RETURN"), remap = false, require = 0, cancellable = true)
    private void csf$switchOff(CallbackInfoReturnable<Boolean> cir) {
        if (csf$off && cir.getReturnValue()) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "write", at = @At("TAIL"), remap = false, require = 0)
    private void csf$write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket, CallbackInfo ci) {
        tag.putBoolean("CsfOff", csf$off);
    }

    @Inject(method = "read", at = @At("TAIL"), remap = false, require = 0)
    private void csf$read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket, CallbackInfo ci) {
        if (tag.contains("CsfOff")) csf$off = tag.getBoolean("CsfOff");
    }

    @Inject(method = "tickBlazeBurnerHeat", at = @At("HEAD"), remap = false, require = 0)
    private void csf$lavaHeat(CallbackInfo ci) {
        if (!CsFluid.stirlingEnabled()) return;

        // Нагрев держится 3 тика, поэтому обновляем раз в 2 тика
        if (++csf$sTimer < 2) return;
        csf$sTimer = 0;

        if (!isEngineActive()) return;

        BlockEntity be = (BlockEntity) (Object) this;
        Level level = be.getLevel();
        if (level == null || level.isClientSide) return;

        BlockPos below = be.getBlockPos().below();
        if (!level.hasChunkAt(below)) return;

        IFluidHandler tank = level.getCapability(Capabilities.FluidHandler.BLOCK, below, Direction.UP);
        if (tank == null) tank = level.getCapability(Capabilities.FluidHandler.BLOCK, below, null);
        if (tank == null) return;

        FluidStack probe = tank.drain(1, FluidAction.SIMULATE);
        if (probe.isEmpty() || !probe.is(FluidTags.LAVA)) return;

        double perSecond = CsFluid.stirlingLavaPerSecond();
        if (perSecond > 0.0) {
            double factor = 1.0;

            // Чем быстрее крутится, тем больше расход (как у нагревателей мода: RPM / 256)
            if (CsFluid.stirlingScaleSpeed()) {
                factor *= Math.min(2.0, Math.abs(getGeneratedSpeed()) / 256.0);
            }

            // Чем больше нагрузка, тем больше расход
            if (CsFluid.stirlingScaleLoad()) {
                if (++csf$loadTimer >= 5) { // обновляем нагрузку раз в 10 тиков
                    csf$loadTimer = 0;
                    csf$loadFrac = csf$measureLoad(be);
                }
                double idle = CsFluid.stirlingIdleFraction();
                factor *= idle + (1.0 - idle) * csf$loadFrac;
            }

            csf$mbAcc += perSecond * factor / 20.0 * 2.0; // за 2 тика
            if (csf$mbAcc >= 1.0) {
                int take = (int) csf$mbAcc;
                FluidStack got = tank.drain(probe.copyWithAmount(take), FluidAction.EXECUTE);
                if (got.isEmpty()) return; // лавы не хватило — тепла нет
                csf$mbAcc -= got.getAmount();
            }
        }

        // Тот же вызов, которым нагреватели «кормят» двигатель теплом: включает работу и обновляет вращение
        consumeHeat(1.0f, 1.0f, false);
    }

    /** Доля нагрузки на этот двигатель (0..1). Если прочитать сеть не удалось — считаем нагрузку полной. */
    @Unique
    private float csf$measureLoad(BlockEntity be) {
        try {
            AutoLoad.Reading r = AutoLoad.read(be);
            if (r == null) return 1.0f;
            float own = r.own >= 0f ? r.own : r.capacity;
            if (own <= 1f) return 1.0f;
            float others = Math.max(0f, r.capacity - own);
            float used = Math.max(0f, r.stress - others);
            return Math.max(0f, Math.min(1f, used / own));
        } catch (Throwable t) {
            return 1.0f;
        }
    }
}

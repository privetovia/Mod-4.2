package com.example.csfluid;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction;
import net.neoforged.neoforge.items.ItemStackHandler;

/** Забор лавы из разрешённых баков, стоящих вплотную к двигателю. */
public final class TankPuller {
    private TankPuller() {}

    private static Field FUEL_FIELD;
    private static boolean fuelFieldFailed = false;

    /** Лежит ли в топливном слоте двигателя любой предмет. */
    public static boolean hasFuelItem(BlockEntity engine) {
        if (fuelFieldFailed) return false;
        try {
            if (FUEL_FIELD == null) {
                FUEL_FIELD = engine.getClass().getField("fuel");
            }
            Object o = FUEL_FIELD.get(engine);
            if (o instanceof ItemStackHandler handler) {
                for (int i = 0; i < handler.getSlots(); i++) {
                    if (!handler.getStackInSlot(i).isEmpty()) return true;
                }
            }
            return false;
        } catch (Throwable t) {
            fuelFieldFailed = true;
            CsFluid.LOG.warn("[csfluid] Не удалось проверить топливный слот двигателя, "
                    + "правило «предмет в двигателе» отключено", t);
            return false;
        }
    }

    public static void tickPull(BlockEntity engineBe, AutoToggle toggle) {
        if (!CsFluid.pullTanksEnabled()) return;

        Level level = engineBe.getLevel();
        if (level == null || level.isClientSide()) return;

        Set<BlockPos> allowed = toggle.csfGetAllowedTanks();
        if (allowed.isEmpty()) {
            toggle.csfSetActiveTanks(0);
            return;
        }

        BlockPos enginePos = engineBe.getBlockPos();

        // Разрешения для блоков, которые не стоят вплотную к двигателю, не могут работать — забываем их
        Set<BlockPos> adjacent = new HashSet<>();
        for (Direction dir : Direction.values()) adjacent.add(enginePos.relative(dir));
        if (allowed.removeIf(pos -> !adjacent.contains(pos))) {
            engineBe.setChanged();
        }

        List<IFluidHandler> tanks = new ArrayList<>();
        List<BlockPos> stale = new ArrayList<>();

        // Только 6 соседних блоков (по граням), не диагонали
        for (Direction dir : Direction.values()) {
            BlockPos pos = enginePos.relative(dir);
            if (!allowed.contains(pos)) continue;
            if (!level.hasChunkAt(pos)) continue; // не трогаем незагруженные чанки

            IFluidHandler handler = level.getCapability(Capabilities.FluidHandler.BLOCK, pos, dir.getOpposite());
            if (handler == null) {
                handler = level.getCapability(Capabilities.FluidHandler.BLOCK, pos, null);
            }
            if (handler == null) {
                stale.add(pos); // бак убрали или заменили — забываем разрешение
                continue;
            }

            FluidStack probe = handler.drain(1, FluidAction.SIMULATE);
            if (probe.isEmpty() || !probe.is(FluidTags.LAVA)) continue;

            // Один и тот же бак (например, многоблочный) мог попасть дважды — считаем его один раз
            boolean duplicate = false;
            for (IFluidHandler known : tanks) {
                if (known == handler) {
                    duplicate = true;
                    break;
                }
            }
            if (!duplicate) tanks.add(handler);
        }

        if (!stale.isEmpty()) {
            allowed.removeAll(stale);
            engineBe.setChanged();
        }

        toggle.csfSetActiveTanks(tanks.size());
        if (tanks.isEmpty()) return;

        // Если в двигателе лежит предмет-топливо — лаву не качаем
        if (CsFluid.pullSkipWhenFuelItem() && hasFuelItem(engineBe)) return;

        CsFluid.EngineLavaHandler engine = new CsFluid.EngineLavaHandler(engineBe);

        // Сколько лавы нужно докачать, чтобы в двигателе было не больше целевого запаса
        int room = Math.min(CsFluid.pullTargetBuffer() - engine.getBufferMb(), CsFluid.pullMaxRate());
        if (room <= 0) return;

        int remaining = room;

        if (CsFluid.pullDrainMode() == CsFluid.DrainMode.EVEN && tanks.size() > 1) {
            int share = Math.max(1, (int) Math.ceil(remaining / (double) tanks.size()));
            for (IFluidHandler tank : tanks) {
                if (remaining <= 0) break;
                remaining -= move(tank, engine, Math.min(share, remaining));
            }
        }
        // SEQUENTIAL, а также добор остатка в режиме EVEN
        for (IFluidHandler tank : tanks) {
            if (remaining <= 0) break;
            remaining -= move(tank, engine, remaining);
        }
    }

    /**
     * Безопасно переливает лаву: сначала проверяет оба конца в SIMULATE,
     * потом забирает из бака и кладёт в двигатель. Остаток (если двигатель взял меньше) возвращает в бак.
     * @return сколько мБ реально перелито
     */
    private static int move(IFluidHandler tank, IFluidHandler engine, int max) {
        if (max <= 0) return 0;

        FluidStack sim = tank.drain(max, FluidAction.SIMULATE);
        if (sim.isEmpty() || !sim.is(FluidTags.LAVA)) return 0;

        int canFill = engine.fill(sim, FluidAction.SIMULATE);
        if (canFill <= 0) return 0;

        FluidStack drained = tank.drain(sim.copyWithAmount(Math.min(canFill, sim.getAmount())), FluidAction.EXECUTE);
        if (drained.isEmpty()) return 0;

        int accepted = engine.fill(drained, FluidAction.EXECUTE);
        if (accepted < drained.getAmount()) {
            FluidStack rest = drained.copyWithAmount(drained.getAmount() - accepted);
            int back = tank.fill(rest, FluidAction.EXECUTE);
            if (back < rest.getAmount()) {
                CsFluid.LOG.warn("[csfluid] Не удалось вернуть {} мБ лавы в бак", rest.getAmount() - back);
            }
        }
        return accepted;
    }
}

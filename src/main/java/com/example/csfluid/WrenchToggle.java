package com.example.csfluid;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;

/**
 * ПКМ гаечным ключом Create (в основной руке, без приседания):
 *  - по двигателю = переключить авто-режим нагрузки;
 *  - по баку, стоящему вплотную к двигателю = разрешить/запретить двигателю выкачивать из него лаву.
 */
public final class WrenchToggle {
    private WrenchToggle() {}

    private static final ResourceLocation WRENCH_ID = ResourceLocation.fromNamespaceAndPath("create", "wrench");

    private static final String IWRENCHABLE = "com.simibubi.create.content.equipment.wrench.IWrenchable";
    private static boolean rotateFailed = false;

    /** Поворот блока тем же способом, что и штатный ключ Create (через IWrenchable.onWrenched). */
    private static void rotateWithWrench(PlayerInteractEvent.RightClickBlock event, Level level, BlockPos pos) {
        if (rotateFailed) return;
        try {
            BlockState state = level.getBlockState(pos);
            Class<?> wrenchable = Class.forName(IWRENCHABLE);
            if (!wrenchable.isInstance(state.getBlock())) return; // не поворачивается — оставляем штатное поведение

            Method onWrenched = wrenchable.getMethod("onWrenched", BlockState.class, UseOnContext.class);
            UseOnContext ctx = new UseOnContext(level, event.getEntity(), event.getHand(),
                    event.getItemStack(), event.getHitVec());
            Object result = onWrenched.invoke(state.getBlock(), state, ctx);

            // В любом случае отменяем штатное действие ключа (чтобы блок не разобрался)
            event.setCancellationResult(result instanceof InteractionResult r && r.consumesAction()
                    ? InteractionResult.SUCCESS : InteractionResult.FAIL);
            event.setCanceled(true);
        } catch (Throwable t) {
            rotateFailed = true;
            CsFluid.LOG.warn("[csfluid] Поворот Stirling Engine через ключ не удался, "
                    + "присед + ПКМ работает штатно (разбирает блок)", t);
        }
    }

    /** Отправляет клиентам обновлённые данные блока (чтобы очки сразу показали новое состояние). */
    private static void syncToClient(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        level.sendBlockUpdated(pos, state, state, 3);
    }

    public static void onRightClick(PlayerInteractEvent.RightClickBlock event) {
        Level level = event.getLevel();
        if (level.isClientSide()) return;
        if (!CsFluid.wrenchToggle()) return;

        Player player = event.getEntity();

        ItemStack stack = event.getItemStack();
        if (!WRENCH_ID.equals(BuiltInRegistries.ITEM.getKey(stack.getItem()))) return;

        BlockPos clickedPos = event.getPos();
        BlockEntity be = level.getBlockEntity(clickedPos);
        if (be == null) return;

        // 0. Stirling Engine: ПКМ = включить/выключить, присед + ПКМ = повернуть
        if (be instanceof StirlingSwitch stirling) {
            if (!CsFluid.stirlingWrenchSwitch()) return;
            if (player.isShiftKeyDown()) {
                rotateWithWrench(event, level, clickedPos);
                return;
            }
            boolean off = !stirling.csfIsOff();
            stirling.csfSetOff(off);
            be.setChanged();
            syncToClient(level, clickedPos);

            Component state = Component.literal(off ? "ВЫКЛЮЧЕН" : "ВКЛЮЧЁН")
                    .withStyle(off ? ChatFormatting.RED : ChatFormatting.GREEN);
            player.displayClientMessage(
                    Component.literal("Stirling Engine: ").withStyle(ChatFormatting.GRAY).append(state), true);

            event.setCancellationResult(InteractionResult.SUCCESS);
            event.setCanceled(true);
            return;
        }

        // Для остальных блоков присед + ключ = штатный разбор
        if (player.isShiftKeyDown()) return;

        // 1. Клик по самому двигателю: перебор режимов
        //    0: Ручной, трубы выкл -> 1: Авто, трубы выкл -> 2: Ручной, трубы вкл -> 3: Авто, трубы вкл -> 0 ...
        if (be instanceof AutoToggle toggle) {
            int idx = (toggle.csfIsAuto() ? 1 : 0) | (toggle.csfIsPipes() ? 2 : 0);
            int next = (idx + 1) % 4;
            boolean auto = (next & 1) != 0;
            boolean pipes = (next & 2) != 0;
            toggle.csfSetAuto(auto);
            toggle.csfSetPipes(pipes);
            boolean cleared = false;
            if (next == 0 && !toggle.csfGetAllowedTanks().isEmpty()) { // полный сброс
                toggle.csfGetAllowedTanks().clear();
                toggle.csfSetActiveTanks(0);
                cleared = true;
            }
            be.setChanged();
            syncToClient(level, clickedPos);

            Component autoState = Component.literal(auto ? "Авто" : "Ручной")
                    .withStyle(auto ? ChatFormatting.GREEN : ChatFormatting.GOLD);
            Component pipesState = Component.literal(pipes ? "ВКЛ" : "ВЫКЛ")
                    .withStyle(pipes ? ChatFormatting.GREEN : ChatFormatting.RED);
            player.displayClientMessage(
                    Component.literal("Режим: ").withStyle(ChatFormatting.GRAY)
                            .append(autoState)
                            .append(Component.literal("   Лава по трубам: ").withStyle(ChatFormatting.GRAY))
                            .append(pipesState)
                            .append(Component.literal("   [" + (next + 1) + "/4]").withStyle(ChatFormatting.DARK_GRAY))
                            .append(Component.literal(cleared ? "  список баков очищен" : "").withStyle(ChatFormatting.DARK_GRAY)),
                    true);

            event.setCancellationResult(InteractionResult.SUCCESS);
            event.setCanceled(true);
            return;
        }

        // 2. Клик по баку рядом с двигателем: разрешить/запретить забор лавы
        if (!CsFluid.pullTanksEnabled()) return;
        IFluidHandler tankCap = level.getCapability(Capabilities.FluidHandler.BLOCK, clickedPos, event.getFace());
        if (tankCap == null) {
            tankCap = level.getCapability(Capabilities.FluidHandler.BLOCK, clickedPos, null);
        }
        // Блок должен иметь настоящую ёмкость (бак, ядро ходока и т.п.); иначе ключ работает как обычно
        if (tankCap == null || tankCap.getTanks() <= 0) return;

        List<AutoToggle> engines = new ArrayList<>();
        for (Direction dir : Direction.values()) {
            BlockEntity neighbor = level.getBlockEntity(clickedPos.relative(dir));
            if (neighbor instanceof AutoToggle engineToggle) {
                engines.add(engineToggle);
            }
        }
        if (engines.isEmpty()) return;

        // Если у всех соседних двигателей бак уже разрешён — запрещаем, иначе разрешаем всем (единое состояние)
        boolean allAllowed = true;
        for (AutoToggle e : engines) {
            if (!e.csfIsTankAllowed(clickedPos)) {
                allAllowed = false;
                break;
            }
        }
        boolean target = !allAllowed;
        for (AutoToggle e : engines) {
            e.csfSetTankAllowed(clickedPos, target);
            ((BlockEntity) e).setChanged();
            syncToClient(level, ((BlockEntity) e).getBlockPos());
        }

        Component state = Component.literal(target ? "ВКЛЮЧЁН" : "ВЫКЛЮЧЕН")
                .withStyle(target ? ChatFormatting.GREEN : ChatFormatting.RED);
        player.displayClientMessage(
                Component.literal("Забор лавы из бака: ").withStyle(ChatFormatting.GRAY).append(state), true);

        event.setCancellationResult(InteractionResult.SUCCESS);
        event.setCanceled(true);
    }
}

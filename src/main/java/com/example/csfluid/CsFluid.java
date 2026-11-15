package com.example.csfluid;

import java.lang.reflect.Field;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.ICapabilityProvider;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;

/**
 * Аддон для Compact Steam Engine:
 *  1) приём лавы через трубы Create;
 *  2) забор лавы напрямую из соседних баков;
 *  3) автоматический режим нагрузки.
 */
@Mod("csfluid")
public class CsFluid {
    public static final Logger LOG = LogUtils.getLogger();

    static final ResourceLocation ENGINE_ID =
            ResourceLocation.fromNamespaceAndPath("compactsteam", "compact_steam_engine");

    /** сколько тиков горения даёт 1 мБ лавы (ведро = 1000 мБ = 20000 тиков) */
    static final int TICKS_PER_MB = 20;
    /** максимум "запаса" лавы внутри двигателя, в мБ */
    static final int CAPACITY_MB = 4000;

    public enum DrainMode {
        SEQUENTIAL, // опустошать один за другим
        EVEN        // делить поровну между всеми
    }

    // ---------------- конфиг (config/csfluid-common.toml) ----------------
    public static final ModConfigSpec SPEC;

    // Авто-нагрузка
    public static final ModConfigSpec.BooleanValue AUTO_ENABLED;
    public static final ModConfigSpec.IntValue CHECK_INTERVAL;
    public static final ModConfigSpec.IntValue LOWER_DELAY;
    public static final ModConfigSpec.IntValue MIN_STEP;
    public static final ModConfigSpec.DoubleValue MARGIN;
    public static final ModConfigSpec.BooleanValue WRENCH_TOGGLE;
    public static final ModConfigSpec.BooleanValue DEBUG_LOG;

    // Забор из баков
    public static final ModConfigSpec.BooleanValue PULL_TANKS_ENABLED;
    public static final ModConfigSpec.EnumValue<DrainMode> PULL_DRAIN_MODE;
    public static final ModConfigSpec.IntValue PULL_INTERVAL;
    public static final ModConfigSpec.IntValue PULL_MAX_RATE;
    public static final ModConfigSpec.IntValue PULL_TARGET_BUFFER;
    public static final ModConfigSpec.BooleanValue PULL_SKIP_WHEN_ITEM;

    // Приём лавы от труб и Stirling Engine
    public static final ModConfigSpec.BooleanValue PIPE_INTAKE_ENABLED;
    public static final ModConfigSpec.BooleanValue STIRLING_ENABLED;
    public static final ModConfigSpec.DoubleValue STIRLING_LAVA_PER_SEC;
    public static final ModConfigSpec.BooleanValue STIRLING_SCALE_SPEED;
    public static final ModConfigSpec.BooleanValue STIRLING_SCALE_LOAD;
    public static final ModConfigSpec.DoubleValue STIRLING_IDLE_FRACTION;
    public static final ModConfigSpec.BooleanValue STIRLING_WRENCH;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();

        b.push("autoLoad");
        AUTO_ENABLED = b.comment("Автоматический режим нагрузки. Слайдер мощности двигателя становится ЛИМИТОМ.",
                "false = двигатель работает как обычно (фиксированная ступень).")
                .define("enabled", true);
        CHECK_INTERVAL = b.comment("Как часто двигатель пересчитывает нужную мощность (в тиках, 20 тиков = 1 сек).")
                .defineInRange("checkIntervalTicks", 10, 1, 200);
        LOWER_DELAY = b.comment("Сколько тиков нагрузка должна быть ниже, прежде чем мощность снизится (защита от дёрганья).")
                .defineInRange("lowerDelayTicks", 100, 0, 6000);
        MARGIN = b.comment("Запас мощности сверх нагрузки (0.05 = +5%).")
                .defineInRange("safetyMargin", 0.05, 0.0, 1.0);
        MIN_STEP = b.comment("Минимальная ступень мощности на холостом ходу.")
                .defineInRange("minStep", 1, 1, 100);
        WRENCH_TOGGLE = b.comment("ПКМ гаечным ключом Create (в основной руке, без приседания):",
                "по двигателю — включает/выключает авто-режим, по баку рядом — разрешает/запрещает забор лавы.",
                "ВНИМАНИЕ: тогда ключ не поворачивает двигатель. false = ключ работает штатно.")
                .define("wrenchToggle", true);
        DEBUG_LOG = b.comment("Писать в лог каждое изменение ступени (нагрузка, ёмкость). Включайте, когда ищете проблему.")
                .define("debugLog", false);
        b.pop();

        b.push("tankPulling");
        PULL_TANKS_ENABLED = b.comment("Может ли двигатель самостоятельно выкачивать лаву из соседних баков.",
                "false = только приём лавы через трубы.")
                .define("enabled", true);
        PULL_DRAIN_MODE = b.comment("Режим выкачки при наличии нескольких баков:",
                "SEQUENTIAL = опустошает один бак полностью, затем переходит к следующему;",
                "EVEN = выкачивает лаву со всех баков поровну.")
                .defineEnum("drainMode", DrainMode.SEQUENTIAL);
        PULL_INTERVAL = b.comment("Как часто двигатель проверяет и забирает лаву из баков (в тиках).")
                .defineInRange("intervalTicks", 10, 1, 200);
        PULL_MAX_RATE = b.comment("Максимальное количество лавы (в мБ), забираемое за один раз.")
                .defineInRange("maxRateMb", 1000, 1, 4000);
        PULL_TARGET_BUFFER = b.comment("До какого запаса (мБ) двигатель докачивает лаву из баков.",
                "Больше = больше лавы «спрятано» внутри двигателя (при поломке двигателя она пропадает).")
                .defineInRange("targetBufferMb", 1000, 100, 4000);
        PULL_SKIP_WHEN_ITEM = b.comment("true = если в топливном слоте двигателя лежит предмет, лаву из баков не качать.")
                .define("skipWhenFuelItem", true);
        b.pop();

        b.push("pipeIntake");
        PIPE_INTAKE_ENABLED = b.comment("Разрешить двигателям принимать лаву от труб и помп.",
                "Это общий выключатель: у каждого двигателя приём по трубам ещё включается отдельно ключом (по умолчанию выключен).")
                .define("enabled", true);
        b.pop();

        b.push("stirling");
        STIRLING_ENABLED = b.comment("Stirling Engine из Create Propulsion греется от бака с лавой, стоящего ПОД ним.")
                .define("enabled", true);
        STIRLING_LAVA_PER_SEC = b.comment("Расход лавы (мБ в секунду) при 256 RPM и полной нагрузке. 0 = бесплатно.",
                "Реальный расход зависит от скорости и нагрузки (см. ниже).")
                .defineInRange("lavaMbPerSecond", 2.0, 0.0, 100.0);
        STIRLING_SCALE_SPEED = b.comment("Расход растёт со скоростью вращения (RPM / 256), как у самих нагревателей этого мода.")
                .define("scaleWithSpeed", true);
        STIRLING_SCALE_LOAD = b.comment("Расход зависит от реальной нагрузки на двигатель.")
                .define("scaleWithLoad", true);
        STIRLING_IDLE_FRACTION = b.comment("Какая доля расхода остаётся, когда нагрузки нет (0.25 = 25%).")
                .defineInRange("idleFraction", 0.25, 0.0, 1.0);
        STIRLING_WRENCH = b.comment("ПКМ гаечным ключом по Stirling Engine включает/выключает его, а ПКМ с приседанием поворачивает.",
                "false = ключ работает штатно (ПКМ поворачивает, присед + ПКМ разбирает).")
                .define("wrenchSwitch", true);
        b.pop();

        SPEC = b.build();
    }

    public static boolean autoEnabled() {
        try { return AUTO_ENABLED.get(); } catch (Exception e) { return false; }
    }
    public static int interval() {
        try { return CHECK_INTERVAL.get(); } catch (Exception e) { return 10; }
    }
    public static int lowerDelay() {
        try { return LOWER_DELAY.get(); } catch (Exception e) { return 100; }
    }
    public static int minStep() {
        try { return MIN_STEP.get(); } catch (Exception e) { return 1; }
    }
    public static boolean wrenchToggle() {
        try { return WRENCH_TOGGLE.get(); } catch (Exception e) { return true; }
    }
    public static double margin() {
        try { return MARGIN.get(); } catch (Exception e) { return 0.05; }
    }
    public static boolean debugLog() {
        try { return DEBUG_LOG.get(); } catch (Exception e) { return false; }
    }

    public static boolean pullTanksEnabled() {
        try { return PULL_TANKS_ENABLED.get(); } catch (Exception e) { return true; }
    }
    public static DrainMode pullDrainMode() {
        try { return PULL_DRAIN_MODE.get(); } catch (Exception e) { return DrainMode.SEQUENTIAL; }
    }
    public static int pullInterval() {
        try { return PULL_INTERVAL.get(); } catch (Exception e) { return 10; }
    }
    public static int pullMaxRate() {
        try { return PULL_MAX_RATE.get(); } catch (Exception e) { return 1000; }
    }
    public static int pullTargetBuffer() {
        try { return PULL_TARGET_BUFFER.get(); } catch (Exception e) { return 1000; }
    }
    public static boolean pullSkipWhenFuelItem() {
        try { return PULL_SKIP_WHEN_ITEM.get(); } catch (Exception e) { return true; }
    }

    public static boolean pipeIntakeEnabled() {
        try { return PIPE_INTAKE_ENABLED.get(); } catch (Exception e) { return true; }
    }
    public static boolean stirlingEnabled() {
        try { return STIRLING_ENABLED.get(); } catch (Exception e) { return true; }
    }
    public static boolean stirlingScaleSpeed() {
        try { return STIRLING_SCALE_SPEED.get(); } catch (Exception e) { return true; }
    }
    public static boolean stirlingScaleLoad() {
        try { return STIRLING_SCALE_LOAD.get(); } catch (Exception e) { return true; }
    }
    public static double stirlingIdleFraction() {
        try { return STIRLING_IDLE_FRACTION.get(); } catch (Exception e) { return 0.25; }
    }
    public static boolean stirlingWrenchSwitch() {
        try { return STIRLING_WRENCH.get(); } catch (Exception e) { return true; }
    }
    public static double stirlingLavaPerSecond() {
        try { return STIRLING_LAVA_PER_SEC.get(); } catch (Exception e) { return 2.0; }
    }

    public CsFluid(IEventBus modBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.COMMON, SPEC);
        modBus.addListener(this::registerCaps);
        NeoForge.EVENT_BUS.addListener(WrenchToggle::onRightClick);
    }

    @SuppressWarnings("unchecked")
    private void registerCaps(RegisterCapabilitiesEvent event) {
        BlockEntityType<?> type = BuiltInRegistries.BLOCK_ENTITY_TYPE.getOptional(ENGINE_ID).orElse(null);
        if (type == null) {
            LOG.warn("[csfluid] Не найден {} — Compact Steam не установлен?", ENGINE_ID);
            return;
        }
        BlockEntityType<BlockEntity> t = (BlockEntityType<BlockEntity>) type;
        ICapabilityProvider<BlockEntity, Direction, IFluidHandler> provider =
                (be, side) -> new EngineLavaHandler(be, true);
        event.registerBlockEntity(Capabilities.FluidHandler.BLOCK, t, provider);
        LOG.info("[csfluid] Fluid capability зарегистрирована для {}", ENGINE_ID);
    }

    /** Обработчик жидкости двигателя: принимает лаву и добавляет её в burnRemaining. */
    public static class EngineLavaHandler implements IFluidHandler {
        private static Field REMAINING;
        private static Field TOTAL;
        private static boolean failed = false;

        private final BlockEntity be;
        /** true = обращение снаружи (трубы/помпы): учитывается переключатель «лава по трубам» у двигателя */
        private final boolean external;

        public EngineLavaHandler(BlockEntity be) {
            this(be, false);
        }

        public EngineLavaHandler(BlockEntity be, boolean external) {
            this.be = be;
            this.external = external;
            init(be.getClass());
        }

        private boolean externalAllowed() {
            if (!external) return true;
            return CsFluid.pipeIntakeEnabled() && be instanceof AutoToggle t && t.csfIsPipes();
        }

        private static synchronized void init(Class<?> c) {
            if (REMAINING != null || failed) return;
            try {
                REMAINING = c.getDeclaredField("burnRemaining");
                REMAINING.setAccessible(true);
                TOTAL = c.getDeclaredField("burnTotal");
                TOTAL.setAccessible(true);
            } catch (Exception e) {
                failed = true;
                LOG.error("[csfluid] Не удалось получить поля двигателя (версия Compact Steam изменилась?)", e);
            }
        }

        public double getRemaining() {
            if (failed || REMAINING == null) return 0;
            try { return REMAINING.getDouble(be); } catch (Exception e) { return 0; }
        }

        /** Сколько мБ лавы сейчас «лежит» в двигателе в виде запаса горения. */
        public int getBufferMb() {
            return (int) Math.floor(getRemaining() / TICKS_PER_MB);
        }

        @Override public int getTanks() { return 1; }
        @Override public FluidStack getFluidInTank(int tank) { return FluidStack.EMPTY; }
        @Override public int getTankCapacity(int tank) { return CAPACITY_MB; }
        @Override public boolean isFluidValid(int tank, FluidStack stack) {
            return externalAllowed() && stack.is(FluidTags.LAVA);
        }

        @Override
        public int fill(FluidStack resource, FluidAction action) {
            if (failed || REMAINING == null || resource.isEmpty() || !resource.is(FluidTags.LAVA)) return 0;
            if (!externalAllowed()) return 0;

            double remaining = getRemaining();
            int space = (int) Math.floor((CAPACITY_MB * (double) TICKS_PER_MB - remaining) / TICKS_PER_MB);
            int accepted = Math.min(space, resource.getAmount());
            if (accepted <= 0) return 0;

            if (action.execute()) {
                try {
                    double newRemaining = remaining + (double) accepted * TICKS_PER_MB;
                    REMAINING.setDouble(be, newRemaining);
                    int total = TOTAL.getInt(be);
                    if (newRemaining > total) TOTAL.setInt(be, (int) Math.ceil(newRemaining));
                    be.setChanged();
                } catch (Exception e) {
                    LOG.error("[csfluid] Ошибка при добавлении топлива", e);
                    return 0;
                }
            }
            return accepted;
        }

        @Override public FluidStack drain(FluidStack resource, FluidAction action) { return FluidStack.EMPTY; }
        @Override public FluidStack drain(int maxDrain, FluidAction action) { return FluidStack.EMPTY; }
    }
}

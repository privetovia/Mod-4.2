package com.example.csfluid;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * Читает из кинетической сети Create нагрузку и ёмкость (в SU).
 * Всё через рефлексию, чтобы не зависеть от Create при компиляции.
 * Если что-то не находится — возвращает null и один раз пишет причину в лог.
 */
public final class AutoLoad {
    private AutoLoad() {}

    /** Результат одного измерения. */
    public static final class Reading {
        /** суммарная нагрузка сети, SU */
        public final float stress;
        /** суммарная ёмкость сети (все источники), SU */
        public final float capacity;
        /** реальный вклад ЭТОГО двигателя в ёмкость, SU; -1 = узнать не удалось */
        public final float own;
        /** сеть перегружена (Create показывает перегруз) */
        public final boolean overstressed;

        Reading(float stress, float capacity, float own, boolean overstressed) {
            this.stress = stress;
            this.capacity = capacity;
            this.own = own;
            this.overstressed = overstressed;
        }
    }

    private static final String KINETIC_BE = "com.simibubi.create.content.kinetics.base.KineticBlockEntity";

    private static Method HAS_NET, GET_NET, IS_OVER;
    private static Field F_STRESS, F_CAP;
    private static Method M_STRESS, M_CAP, M_ACTUAL_CAP;
    private static Class<?> KINETIC_CLASS;
    private static boolean bound = false;
    private static boolean broken = false;

    /** @return измерение или null, если прочитать сеть Create не вышло (тогда авто-режим молча ничего не делает) */
    public static Reading read(BlockEntity be) {
        if (broken) return null;
        try {
            if (HAS_NET == null) {
                Class<?> c = be.getClass();
                HAS_NET = c.getMethod("hasNetwork");
                GET_NET = c.getMethod("getOrCreateNetwork");
                try {
                    IS_OVER = c.getMethod("isOverStressed");
                } catch (NoSuchMethodException ignored) {
                    IS_OVER = null;
                }
                KINETIC_CLASS = findKineticClass(c);
            }

            if (!((Boolean) HAS_NET.invoke(be))) return new Reading(0f, 0f, 0f, false);
            Object net = GET_NET.invoke(be);
            if (net == null) return new Reading(0f, 0f, 0f, false);
            if (!bound) bind(net.getClass());

            float stress = F_STRESS != null ? F_STRESS.getFloat(net) : ((Number) M_STRESS.invoke(net)).floatValue();
            float cap = F_CAP != null ? F_CAP.getFloat(net) : ((Number) M_CAP.invoke(net)).floatValue();

            boolean over = false;
            if (IS_OVER != null) {
                try { over = (Boolean) IS_OVER.invoke(be); } catch (Throwable ignored) { over = false; }
            }

            float own = -1f;
            if (M_ACTUAL_CAP != null) {
                try { own = ((Number) M_ACTUAL_CAP.invoke(net, be)).floatValue(); } catch (Throwable ignored) { own = -1f; }
            }
            return new Reading(stress, cap, own, over);
        } catch (Throwable t) {
            broken = true;
            CsFluid.LOG.error("[csfluid] Авто-режим отключён: не удалось прочитать сеть Create "
                    + "(другая версия Create?)", t);
            return null;
        }
    }

    private static Class<?> findKineticClass(Class<?> c) {
        while (c != null) {
            if (KINETIC_BE.equals(c.getName())) return c;
            c = c.getSuperclass();
        }
        return null;
    }

    /** true, если блок — кинетический (насос, вал, шестерня...). Для них ключ работает как обычно. */
    public static boolean isKinetic(BlockEntity be) {
        return findKineticClass(be.getClass()) != null;
    }

    private static synchronized void bind(Class<?> netClass) throws Exception {
        if (bound) return;
        try {
            F_STRESS = netClass.getDeclaredField("currentStress");
            F_CAP = netClass.getDeclaredField("currentCapacity");
            F_STRESS.setAccessible(true);
            F_CAP.setAccessible(true);
        } catch (NoSuchFieldException e) {
            F_STRESS = null;
            F_CAP = null;
            M_STRESS = netClass.getMethod("calculateStress");
            M_CAP = netClass.getMethod("calculateCapacity");
        }
        if (KINETIC_CLASS != null) {
            try {
                M_ACTUAL_CAP = netClass.getMethod("getActualCapacityOf", KINETIC_CLASS);
            } catch (NoSuchMethodException e) {
                M_ACTUAL_CAP = null;
            }
        }
        bound = true;
        CsFluid.LOG.info("[csfluid] Авто-режим: сеть Create найдена ({}, точный вклад двигателя: {})",
                netClass.getName(), M_ACTUAL_CAP != null ? "да" : "нет");
    }
}

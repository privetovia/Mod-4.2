package com.example.csfluid;

import java.lang.reflect.Field;

/** Достаёт внешний объект (this$0) у анонимного класса, не зная его тип при компиляции. */
public final class Outer {
    private Outer() {}

    private static Field FIELD;
    private static boolean failed = false;

    public static Object of(Object anonymous) {
        if (failed) return null;
        try {
            if (FIELD == null) {
                FIELD = anonymous.getClass().getDeclaredField("this$0");
                FIELD.setAccessible(true);
            }
            return FIELD.get(anonymous);
        } catch (Throwable t) {
            failed = true;
            CsFluid.LOG.warn("[csfluid] Не удалось получить двигатель из окна настроек (слайдер покажет обычную ступень)", t);
            return null;
        }
    }
}

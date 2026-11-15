package com.example.csfluid;

/** Интерфейс, который миксин добавляет Stirling Engine: ручное выключение ключом. */
public interface StirlingSwitch {
    boolean csfIsOff();
    void csfSetOff(boolean value);
}

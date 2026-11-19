package com.dwallpaper.settings;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 设置白名单钳制单元测试（design.md AC-PST-2：未知键丢弃、越界值钳制）。 */
class SettingsStateClampTest {

    @Test
    void slidersClampedToDefinitiveRanges() {
        SettingsState.State s = new SettingsState.State();
        s.fpsLimit = 999;
        s.blurPx = -5;
        s.brightnessPercent = 1000;
        s.opacityPercent = 200;
        s.idleTimeoutSec = 0;
        SettingsState.State out = SettingsState.clamp(s);
        assertEquals(30, out.fpsLimit);
        assertEquals(0, out.blurPx);
        assertEquals(160, out.brightnessPercent);
        assertEquals(90, out.opacityPercent);   // 定稿口径：上限 90
        assertEquals(5, out.idleTimeoutSec);
    }

    @Test
    void zoneValuesClampedAndUnknownDropped() {
        SettingsState.State s = new SettingsState.State();
        s.fogZones.put("EDITOR", new SettingsState.ZoneState(150, -2, 99, 200));
        s.fogZones.put("GHOST_ZONE", new SettingsState.ZoneState(1, 2, 3, 4)); // 未知区 → 丢弃
        SettingsState.State out = SettingsState.clamp(s);
        assertEquals(6, out.fogZones.size()); // 仅六区
        SettingsState.ZoneState ed = out.fogZones.get("EDITOR");
        assertEquals(100, ed.fogIntensity);
        assertEquals(0, ed.blurRadiusPx);
        assertEquals(30, ed.brightnessOffsetPercent);
        assertEquals(100, ed.opacityPercent);
        assertEquals(null, out.fogZones.get("GHOST_ZONE"));
    }

    @Test
    void customPresetsClamped() {
        SettingsState.State s = new SettingsState.State();
        SettingsState.PresetDto dto = new SettingsState.PresetDto();
        dto.zones.put("EDITOR", new SettingsState.ZoneState(200, 40, -99, 120));
        s.customPresets.put("mine", dto);
        SettingsState.State out = SettingsState.clamp(s);
        SettingsState.ZoneState z = out.customPresets.get("mine").zones.get("EDITOR");
        assertEquals(100, z.fogIntensity);
        assertEquals(30, z.blurRadiusPx);
        assertEquals(-30, z.brightnessOffsetPercent);
        assertEquals(100, z.opacityPercent);
    }
}

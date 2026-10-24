package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ThemePaletteTest {
    @Test public void exposesSixNamedPresetsAndOriginalFallback() {
        assertEquals("Original", ThemePalette.name(ThemePalette.ORIGINAL));
        assertEquals("Nothing Light", ThemePalette.name(ThemePalette.NOTHING_LIGHT));
        assertEquals("Glass Dark", ThemePalette.name(ThemePalette.GLASS_DARK));
        assertEquals("Neo Minimal", ThemePalette.name(ThemePalette.NEO_MINIMAL));
        assertEquals("Industrial Mono", ThemePalette.name(ThemePalette.INDUSTRIAL_MONO));
        assertEquals("Warm Premium", ThemePalette.name(ThemePalette.WARM_PREMIUM));
        assertEquals("Playful Accent", ThemePalette.name(ThemePalette.PLAYFUL_ACCENT));
    }

    @Test public void cyclesOriginalThenAllSixPresets() {
        int value = ThemePalette.ORIGINAL;
        for (int i = 0; i < ThemePalette.COUNT; i++) value = ThemePalette.next(value);
        assertEquals(ThemePalette.PLAYFUL_ACCENT, value);
        assertEquals(ThemePalette.ORIGINAL, ThemePalette.next(value));
    }

    @Test public void originalStillHonoursLegacyLightAndDarkMode() {
        assertTrue(ThemePalette.resolve(ThemePalette.ORIGINAL, true).lightSurface);
        assertFalse(ThemePalette.resolve(ThemePalette.ORIGINAL, false).lightSurface);
    }

    @Test public void industrialIsTheOnlyMonochromePreset() {
        for (int i = 0; i < ThemePalette.COUNT; i++) {
            assertEquals(i == ThemePalette.INDUSTRIAL_MONO,
                    ThemePalette.resolve(i, false).monochromeIcons);
        }
    }
}

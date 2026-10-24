package com.gathin.tvhome;

/** Central visual tokens for the fixed NOTHING TV renderer. */
final class ThemePalette {
    static final int ORIGINAL = -1;
    static final int NOTHING_LIGHT = 0;
    static final int GLASS_DARK = 1;
    static final int NEO_MINIMAL = 2;
    static final int INDUSTRIAL_MONO = 3;
    static final int WARM_PREMIUM = 4;
    static final int PLAYFUL_ACCENT = 5;
    static final int COUNT = 6;

    private static final String[] NAMES = {
            "Nothing Light", "Glass Dark", "Neo Minimal",
            "Industrial Mono", "Warm Premium", "Playful Accent"
    };
    private static final int[] PLAYFUL_APP_TILES = {
            0xff1d55a0, 0xff355ee8, 0xff17233c, 0xff173d70,
            0xff6e356f, 0xff15376a, 0xff244b59, 0xff164e59
    };
    private static final int[] PLAYFUL_BOTTOM_TILES = {
            0xff1379ee, 0xff6334cb, 0xff155b82, 0xff176f69
    };

    final int preset;
    final int backgroundStart, backgroundEnd, glowPrimary, glowSecondary;
    final int panel, panelAlt, text, dim, divider, border;
    final int accent, accentSecondary, accentTertiary, danger;
    final int focusFill, focusText, iconBackground, gridColor;
    final boolean lightSurface, monochromeIcons, grid, multicolor;

    private ThemePalette(int preset, int backgroundStart, int backgroundEnd,
                         int glowPrimary, int glowSecondary, int panel, int panelAlt,
                         int text, int dim, int divider, int border, int accent,
                         int accentSecondary, int accentTertiary, int danger,
                         int focusFill, int focusText, int iconBackground, int gridColor,
                         boolean lightSurface, boolean monochromeIcons,
                         boolean grid, boolean multicolor) {
        this.preset = preset;
        this.backgroundStart = backgroundStart;
        this.backgroundEnd = backgroundEnd;
        this.glowPrimary = glowPrimary;
        this.glowSecondary = glowSecondary;
        this.panel = panel;
        this.panelAlt = panelAlt;
        this.text = text;
        this.dim = dim;
        this.divider = divider;
        this.border = border;
        this.accent = accent;
        this.accentSecondary = accentSecondary;
        this.accentTertiary = accentTertiary;
        this.danger = danger;
        this.focusFill = focusFill;
        this.focusText = focusText;
        this.iconBackground = iconBackground;
        this.gridColor = gridColor;
        this.lightSurface = lightSurface;
        this.monochromeIcons = monochromeIcons;
        this.grid = grid;
        this.multicolor = multicolor;
    }

    static String name(int preset) {
        return preset >= 0 && preset < NAMES.length ? NAMES[preset] : "Original";
    }

    static int next(int preset) {
        return preset < ORIGINAL || preset >= PLAYFUL_ACCENT ? ORIGINAL : preset + 1;
    }

    int appTile(int index) {
        return multicolor ? PLAYFUL_APP_TILES[Math.max(0, Math.min(PLAYFUL_APP_TILES.length - 1, index))] : panel;
    }

    int bottomTile(int index) {
        return multicolor ? PLAYFUL_BOTTOM_TILES[Math.max(0, Math.min(PLAYFUL_BOTTOM_TILES.length - 1, index))] : panel;
    }

    int shadowColor() {
        switch (preset) {
            case NOTHING_LIGHT: return 0x10000000;
            case GLASS_DARK: return 0x30000000;
            case NEO_MINIMAL: return 0x0a000000;
            case WARM_PREMIUM: return 0x30000000;
            case PLAYFUL_ACCENT: return 0x24000000;
            default: return 0x00000000;
        }
    }

    static ThemePalette resolve(int preset, boolean originalLight) {
        switch (preset) {
            case NOTHING_LIGHT:
                return new ThemePalette(preset,
                        0xfff7f6f1, 0xffebeae4, 0x00000000, 0x00000000,
                        0xfffefefd, 0xfff2f1ec, 0xff17191c, 0xff66696c,
                        0x1f17191c, 0x33999b9b, 0xffef3340, 0xff9da0a1,
                        0xff17191c, 0xffef3340, 0xffef3340, 0xffffffff,
                        0xfff7f6f1, 0x00000000, true, false, false, false);
            case GLASS_DARK:
                return new ThemePalette(preset,
                        0xff07111f, 0xff17162d, 0x38ff2f58, 0x336d5cff,
                        0xd91a263b, 0xd91d2942, 0xfff7f8fb, 0xffaeb8c8,
                        0x38b7c8eb, 0x55b7c8eb, 0xffff3151, 0xffb98ae8,
                        0xff4f8cff, 0xffff3151, 0xffff3151, 0xffffffff,
                        0xffeef3f8, 0x00000000, false, false, false, false);
            case NEO_MINIMAL:
                return new ThemePalette(preset,
                        0xfff3f1ea, 0xffe9e7df, 0x00000000, 0x00000000,
                        0xfff8f7f2, 0xffeeece6, 0xff18191b, 0xff686966,
                        0x2418191b, 0x337b7b78, 0xffef4045, 0xffcbc8c1,
                        0xff202124, 0xffef4045, 0xff18191b, 0xffffffff,
                        0xfff7f5ef, 0x00000000, true, false, false, false);
            case INDUSTRIAL_MONO:
                return new ThemePalette(preset,
                        0xff070909, 0xff0d1011, 0x00000000, 0x00000000,
                        0xb30a0d0e, 0xff111516, 0xfff4f5f2, 0xffa4aaa7,
                        0x55f4f5f2, 0x99cdd2d0, 0xffef3f45, 0xff777b7a,
                        0xfff4f5f2, 0xffef3f45, 0xfff4f5f2, 0xff080a0b,
                        0xffe9ece9, 0x16ffffff, false, true, true, false);
            case WARM_PREMIUM:
                return new ThemePalette(preset,
                        0xff1a100c, 0xff382116, 0x42e66a3f, 0x24f1b27b,
                        0xe3472e23, 0xe336231b, 0xffffead7, 0xffcdb39d,
                        0x44f7c59f, 0x55efb17f, 0xffff9868, 0xffd69568,
                        0xff9b664d, 0xfff06443, 0xffffa476, 0xff25130d,
                        0xffffe7d2, 0x00000000, false, false, false, false);
            case PLAYFUL_ACCENT:
                return new ThemePalette(preset,
                        0xff07142b, 0xff102653, 0x356f48ff, 0x2b20c9b1,
                        0xe0162949, 0xe0123857, 0xfff8f9ff, 0xffbbc8dc,
                        0x385c91de, 0x555f88c4, 0xff8d5cff, 0xff2585ff,
                        0xff54d8b5, 0xffff3151, 0xff8d5cff, 0xffffffff,
                        0xffedf3ff, 0x00000000, false, false, false, true);
            default:
                if (originalLight) {
                    return new ThemePalette(ORIGINAL,
                            0xfff2f0ea, 0xfff2f0ea, 0x00000000, 0x00000000,
                            0xffdfdcd5, 0xffe9e6df, 0xff171717, 0xff5e5c57,
                            0x22000000, 0x00000000, 0xffe5433d, 0xff888888,
                            0xff171717, 0xffe5433d, 0xffffffff, 0xff171717,
                            0xfff6f3ec, 0x00000000, true, true, false, false);
                }
                return new ThemePalette(ORIGINAL,
                        0xff191919, 0xff191919, 0x00000000, 0x00000000,
                        0xff292929, 0xff242424, 0xfff2f1ed, 0xffaaa9a6,
                        0x22ffffff, 0x00000000, 0xffe5433d, 0xff888888,
                        0xfff2f1ed, 0xffe5433d, 0xfff3f1ec, 0xff171717,
                        0xffeeeeea, 0x00000000, false, true, false, false);
        }
    }
}

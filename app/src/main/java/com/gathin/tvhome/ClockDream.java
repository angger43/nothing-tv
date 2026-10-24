package com.gathin.tvhome;

import android.service.dreams.DreamService;

/** Interactive-off ambient screen: any physical remote key returns to the previous app. */
public final class ClockDream extends DreamService {
    @Override public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);
        setFullscreen(true);
        setScreenBright(false);
        setContentView(new NothingDreamView(this));
    }
}

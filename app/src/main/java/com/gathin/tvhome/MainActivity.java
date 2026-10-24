package com.gathin.tvhome;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.speech.RecognizerIntent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

/** Lightweight NOTHING (TV) launcher shell. */
public final class MainActivity extends Activity {
    static final String ACTION_VIEW_INPUTS = "com.android.tv.action.VIEW_INPUTS";
    static final String ACTION_LAUNCHER_SETTINGS = "com.gathin.tvhome.LAUNCHER_SETTINGS";
    static final String ACTION_QUICK_SETTINGS = "com.gathin.tvhome.QUICK_SETTINGS";

    private NothingLauncherView launcher;
    private static final int VOICE_SEARCH = 42;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        launcher = new NothingLauncherView(this);
        setContentView(launcher);
        route(getIntent());
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        route(intent);
    }

    private void route(Intent intent) {
        if (launcher == null || intent == null) return;
        String requested = intent.getStringExtra("screen");
        if (requested != null) { launcher.openScreen(requested); return; }
        String action = intent.getAction();
        if (ACTION_VIEW_INPUTS.equals(action)) launcher.openInputs();
        else if (ACTION_LAUNCHER_SETTINGS.equals(action)) launcher.openPersonalize();
        else if (ACTION_QUICK_SETTINGS.equals(action)) launcher.openQuickSettings();
        else launcher.openHome();
    }

    void startVoiceSearch() {
        Intent voice = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PROMPT, "Search installed apps");
        try { startActivityForResult(voice, VOICE_SEARCH); }
        catch (RuntimeException unavailable) { launcher.showUnavailable("Voice search is unavailable on this TV"); }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != VOICE_SEARCH || resultCode != RESULT_OK || data == null) return;
        java.util.ArrayList<String> results = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
        if (results != null && !results.isEmpty()) launcher.setSearchQuery(results.get(0));
    }

    @Override protected void onResume() {
        super.onResume();
        if (launcher != null) launcher.resumeUi();
    }

    @Override protected void onPause() {
        if (launcher != null) launcher.pauseUi();
        super.onPause();
    }

    @Override public void onBackPressed() {
        if (launcher == null || !launcher.goBack()) super.onBackPressed();
    }
}

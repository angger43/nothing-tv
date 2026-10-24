package com.gathin.tvhome;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.media.AudioManager;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;
import android.widget.Toast;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Fixed-reference renderer for the six supplied NOTHING (TV) boards. It deliberately uses Canvas,
 * two typefaces, and no bitmap backgrounds/blur so a low-memory TV does not allocate a hierarchy
 * for every screen. Every focus target is registered into one spatial D-pad graph.
 */
final class NothingLauncherView extends View {
    private static final float W = 720f, H = 405f;
    private static final ColorMatrixColorFilter MONO;
    static {
        ColorMatrix m = new ColorMatrix(); m.setSaturation(0f); MONO = new ColorMatrixColorFilter(m);
    }

    enum Screen { HOME, APPS, INPUTS, QUICK, APP_OPTIONS, SEARCH, SETTINGS, NETWORK,
        ACCESSORIES, PICTURE_SOUND, MEDIA, PRAYER, APP_STORAGE, PERSONALIZE, LIVE_TV, AMBIENT }

    static final class App {
        final String pkg, label; final Intent launch; final Drawable icon;
        App(String pkg, String label, Intent launch, Drawable icon) {
            this.pkg = pkg; this.label = label; this.launch = launch; this.icon = icon;
        }
    }
    static final class Item {
        final String id; final RectF rect; final Runnable click, hold;
        Item(String id, RectF rect, Runnable click, Runnable hold) {
            this.id=id; this.rect=rect; this.click=click; this.hold=hold;
        }
    }

    private final Activity activity;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint backgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final Typeface body, bodyMedium;
    private final SharedPreferences prefs;
    private final Handler clock = new Handler(Looper.getMainLooper());
    private final ExecutorService loader = Executors.newSingleThreadExecutor();
    private final ArrayList<Item> items = new ArrayList<>();
    private final ArrayList<App> apps = new ArrayList<>();
    private final Map<String, App> appByPackage = new HashMap<>();
    private Screen screen = Screen.HOME, previous = Screen.HOME;
    private String focusId = "fav0", selectedPackage, query = "";
    private int appPage;
    private float sx = 1f, sy = 1f;
    private boolean longHandled;
    private long enterDown;
    private boolean paused;

    private int bg, panel, panel2, text, dim, divider, accent, danger, border;
    private boolean light;
    private ThemePalette palette;
    private Shader backgroundShader, glowPrimaryShader, glowSecondaryShader;
    private int backgroundKey = Integer.MIN_VALUE;
    private final Runnable ticker = new Runnable() {
        @Override public void run() { if (!paused) { invalidate(); clock.postDelayed(this, 1000); } }
    };

    NothingLauncherView(Activity activity) {
        super(activity); this.activity = activity;
        setFocusable(true); setFocusableInTouchMode(true); requestFocus();
        Typeface loaded;
        try { loaded = Typeface.createFromAsset(activity.getAssets(), "font/outfit.ttf"); }
        catch (RuntimeException e) { loaded = Typeface.create("sans-serif", Typeface.NORMAL); }
        body = loaded; bodyMedium = Typeface.create(loaded, Typeface.BOLD);
        prefs = activity.getSharedPreferences("nothing_tv", Context.MODE_PRIVATE);
        loadApps();
    }

    void resumeUi() { paused=false; clock.removeCallbacks(ticker); clock.post(ticker); loadApps(); requestFocus(); }
    void pauseUi() { paused=true; clock.removeCallbacks(ticker); }
    void openHome() { show(Screen.HOME, "fav0"); }
    void openInputs() { show(Screen.INPUTS, "input0"); }
    void openQuickSettings() { show(Screen.QUICK, "quick0"); }
    void openPersonalize() { show(Screen.PERSONALIZE, "widget_clock"); }
    void openScreen(String name) {
        try { Screen value=Screen.valueOf(name.toUpperCase(Locale.ROOT)); if(value==Screen.APP_OPTIONS&&!apps.isEmpty())selectedPackage=apps.get(0).pkg; show(value,null); }
        catch (IllegalArgumentException ignored) { openHome(); }
    }
    void setSearchQuery(String value) { query=value==null?"":value; show(Screen.SEARCH,"result0"); }
    void showUnavailable(String message) { toast(message); }
    boolean goBack() {
        if (screen == Screen.HOME) return true;
        if (screen == Screen.APP_OPTIONS) show(previous == Screen.HOME ? Screen.HOME : Screen.APPS, null);
        else show(Screen.HOME, "fav0");
        return true;
    }

    private void show(Screen next, String preferred) {
        if (screen != next) previous = screen;
        screen = next; focusId = preferred; appPage = 0; invalidate(); requestFocus();
    }

    private void loadApps() {
        PackageManager pm = activity.getPackageManager();
        loader.execute(() -> {
            LinkedHashMap<String, ResolveInfo> found = new LinkedHashMap<>();
            for (String cat : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
                Intent q = new Intent(Intent.ACTION_MAIN).addCategory(cat);
                for (ResolveInfo ri : pm.queryIntentActivities(q, 0)) {
                    if (ri.activityInfo == null || !ri.activityInfo.exported) continue;
                    String pkg = ri.activityInfo.packageName;
                    if (!pkg.equals(activity.getPackageName())) found.putIfAbsent(pkg, ri);
                }
            }
            ArrayList<App> loaded = new ArrayList<>();
            for (Map.Entry<String, ResolveInfo> e : found.entrySet()) {
                try {
                    ResolveInfo ri=e.getValue(); String label=String.valueOf(ri.loadLabel(pm));
                    Intent launch=pm.getLeanbackLaunchIntentForPackage(e.getKey());
                    if (launch==null) launch=pm.getLaunchIntentForPackage(e.getKey());
                    if (launch==null) launch=new Intent(Intent.ACTION_MAIN).setClassName(e.getKey(),ri.activityInfo.name);
                    loaded.add(new App(e.getKey(), label, launch, ri.loadIcon(pm)));
                } catch (RuntimeException ignored) { }
            }
            loaded.sort(Comparator.comparing(a -> a.label.toLowerCase(Locale.ROOT)));
            clock.post(() -> {
                apps.clear(); appByPackage.clear();
                apps.addAll(loaded); for (App a:apps) appByPackage.put(a.pkg,a);
                seedFavorites(); invalidate();
            });
        });
    }

    private void seedFavorites() {
        if (prefs.contains("favorites")) return;
        ArrayList<String> chosen = new ArrayList<>();
        try {
            for (String pkg : activity.getResources().getStringArray(R.array.dock_apps))
                if (appByPackage.containsKey(pkg)) chosen.add(pkg);
        } catch (RuntimeException ignored) { }
        for (App a:apps) if (chosen.size()<7 && !chosen.contains(a.pkg)) chosen.add(a.pkg);
        prefs.edit().putString("favorites", TextUtils.join(",", chosen)).apply();
    }

    private List<App> favorites() {
        ArrayList<App> result=new ArrayList<>();
        String saved=prefs.getString("favorites","");
        for(String pkg:saved.split(",")) { App a=appByPackage.get(pkg); if(a!=null) result.add(a); }
        if(result.isEmpty()) for(int i=0;i<Math.min(7,apps.size());i++) result.add(apps.get(i));
        return result;
    }

    private Set<String> hidden() {
        return new HashSet<>(java.util.Arrays.asList(prefs.getString("hidden","").split(",")));
    }

    @Override protected void onDraw(Canvas raw) {
        super.onDraw(raw);
        sx=getWidth()/W; sy=getHeight()/H; raw.save(); raw.scale(sx,sy);
        applyPalette(); drawThemeBackground(raw); items.clear();
        // Centre each board from its actual content bounds; the source grids have different widths.
        float boardOffset=secondaryXOffset();
        if(boardOffset!=0) raw.translate(boardOffset,0);
        switch(screen) {
            case HOME: drawHome(raw); break; case APPS: drawApps(raw); break;
            case INPUTS: drawInputs(raw); break; case QUICK: drawQuick(raw); break;
            case APP_OPTIONS: drawAppOptions(raw); break; case SEARCH: drawSearch(raw); break;
            case SETTINGS: drawSettings(raw); break; case NETWORK: drawNetwork(raw); break;
            case ACCESSORIES: drawAccessories(raw); break; case PICTURE_SOUND: drawPictureSound(raw); break;
            case MEDIA: drawMedia(raw); break; case PRAYER: drawPrayer(raw); break;
            case APP_STORAGE: drawAppStorage(raw); break; case PERSONALIZE: drawPersonalize(raw); break;
            case LIVE_TV: drawLiveTv(raw); break; case AMBIENT: drawAmbient(raw); break;
        }
        if (focusId == null && !items.isEmpty()) { focusId=items.get(0).id; postInvalidate(); }
        raw.restore();
    }

    private void applyPalette() {
        int mode=prefs.getInt("theme_mode",1); Calendar c=Calendar.getInstance();
        boolean originalLight=mode==0 || (mode==2 && c.get(Calendar.HOUR_OF_DAY)>=prefs.getInt("light_start",6)
                && c.get(Calendar.HOUR_OF_DAY)<prefs.getInt("dark_start",18));
        int preset=prefs.getInt("theme_preset",ThemePalette.ORIGINAL);
        if(preset<ThemePalette.ORIGINAL||preset>=ThemePalette.COUNT)preset=ThemePalette.ORIGINAL;
        palette=ThemePalette.resolve(preset,originalLight);light=palette.lightSurface;
        bg=palette.backgroundStart;panel=palette.panel;panel2=palette.panelAlt;text=palette.text;
        dim=palette.dim;divider=palette.divider;accent=palette.accent;danger=palette.danger;border=palette.border;
        int key=(preset+2)*2+(originalLight?1:0);if(key!=backgroundKey){prepareThemeBackground();backgroundKey=key;}
    }

    private void prepareThemeBackground(){
        backgroundShader=palette.backgroundStart==palette.backgroundEnd?null:
                new LinearGradient(0,0,W,H,palette.backgroundStart,palette.backgroundEnd,Shader.TileMode.CLAMP);
        glowPrimaryShader=palette.glowPrimary==0?null:new RadialGradient(610,75,255,
                new int[]{palette.glowPrimary,0x00000000},null,Shader.TileMode.CLAMP);
        glowSecondaryShader=palette.glowSecondary==0?null:new RadialGradient(105,365,285,
                new int[]{palette.glowSecondary,0x00000000},null,Shader.TileMode.CLAMP);
    }

    private void drawThemeBackground(Canvas c){
        if(backgroundShader==null)c.drawColor(bg);else{backgroundPaint.setShader(backgroundShader);c.drawRect(0,0,W,H,backgroundPaint);}
        if(glowPrimaryShader!=null){glowPaint.setShader(glowPrimaryShader);c.drawRect(0,0,W,H,glowPaint);}
        if(glowSecondaryShader!=null){glowPaint.setShader(glowSecondaryShader);c.drawRect(0,0,W,H,glowPaint);}
        glowPaint.setShader(null);
        if(palette.grid){for(float x=0;x<=W;x+=36)line(c,x,0,x,H,.45f,palette.gridColor);for(float y=0;y<=H;y+=27)line(c,0,y,W,y,.45f,palette.gridColor);}
    }

    private void drawHome(Canvas c) {
        // Wordmark and compact navigation.
        dot(c,"NOTHING (TV)",48,30,1.0f,text);
        String[] nav={"Home","Apps","Live TV","Media"};
        for(int i=0;i<nav.length;i++) {
            float x=185+i*69; RectF r=new RectF(x,22,x+58,48);
            boolean active=i==0, f=focused("nav"+i); if(active||f) round(c,r,13,f?focusFill():activeNavFill());
            label(c,nav[i],r.centerX(),39,11,f?focusText():active?activeNavText():text,Paint.Align.CENTER,bodyMedium);
            final int n=i; add("nav"+i,r,()->{ if(n==1)show(Screen.APPS,"app0"); else if(n==2)show(Screen.LIVE_TV,"tv0"); else if(n==3)show(Screen.MEDIA,"media0"); },null);
        }
        iconButton(c,"search",510,"⌕",()->show(Screen.SEARCH,"key0"));
        iconButton(c,"inputtop",550,"↪",()->show(Screen.INPUTS,"input0"));
        iconButton(c,"settingtop",590,"⚙",()->show(Screen.SETTINGS,"settings0"));
        RectF profile=new RectF(650,22,678,50); round(c,profile,14,danger); label(c,"P",664,42,12,Color.WHITE,Paint.Align.CENTER,bodyMedium);
        add("profile",profile,()->openSystem(Settings.ACTION_SYNC_SETTINGS),null);

        drawAnalog(c,116,130,62);
        Calendar now=Calendar.getInstance();
        RectF date=new RectF(190,82,298,218); round(c,date,14,panel);
        label(c,new SimpleDateFormat("EEEE",Locale.getDefault()).format(now.getTime()).toUpperCase(Locale.ROOT),203,104,10,dim,Paint.Align.LEFT,bodyMedium);
        dot(c,new SimpleDateFormat("dd",Locale.getDefault()).format(now.getTime()),203,121,7.4f,text);
        label(c,new SimpleDateFormat("MMM  /  yyyy",Locale.getDefault()).format(now.getTime()).toUpperCase(Locale.ROOT),203,200,10,accent,Paint.Align.LEFT,bodyMedium);

        drawPrayerHome(c,306,82,202,136,now);
        drawHomeControls(c);

        label(c,"YOUR APPS",55,244,11,dim,Paint.Align.LEFT,bodyMedium);
        label(c,"Hold OK to arrange",665,244,10,dim,Paint.Align.RIGHT,body);
        List<App> fav=favorites();
        for(int i=0;i<8;i++) {
            float x=55+i*79; RectF r=new RectF(x,256,x+66,316); App a=i<7&&i<fav.size()?fav.get(i):null; boolean allApps=i==7;
            String itemId=allApps?"home_all_apps":"fav"+i;
            boolean f=focused(itemId); if(f) { round(c,new RectF(r.left-4,r.top-4,r.right+4,r.bottom+4),18,text); round(c,r,15,accent); }
            else round(c,r,15,homeAppFill(i));
            if(allApps) {
                for(int row=0;row<3;row++)for(int col=0;col<3;col++)circle(c,r.centerX()-9+col*9,r.centerY()-10+row*9,1.8f,f?Color.WHITE:text);
                label(c,"All apps",r.centerX(),334,9,text,Paint.Align.CENTER,body);
                add(itemId,r,()->show(Screen.APPS,"app0"),null);
            } else if(a!=null) {
                drawAppIcon(c,a,r.centerX(),r.centerY()-2,34,f); label(c,a.label,r.centerX(),334,9,text,Paint.Align.CENTER,body);
                final App app=a; add(itemId,r,()->launch(app),()->options(app));
            }
        }
        homeBottom(c,"bottom_tv",40,"▣","Live TV",()->show(Screen.LIVE_TV,"tv0"));
        homeBottom(c,"bottom_media",203,"□","Media library",()->show(Screen.MEDIA,"media0"));
        homeBottom(c,"bottom_custom",366,"≡","Customize",()->show(Screen.PERSONALIZE,"widget_clock"));
        homeBottom(c,"bottom_quick",529,"⚙","Quick settings",()->show(Screen.QUICK,"quick0"));
        footer(c,"01  /  HOME");
    }

    private void drawPrayerHome(Canvas c,float x,float y,float w,float h,Calendar now) {
        RectF r=new RectF(x,y,x+w,y+h); round(c,r,14,panel);
        label(c,"PRAYER TIMES",x+13,y+21,10,text,Paint.Align.LEFT,bodyMedium);
        PrayerTimes pt=prayers(now); int next=pt.nextIndex(now), until=pt.minutesUntilNext(now);
        pill(c,new RectF(x+w-72,y+10,x+w-13,y+29),"NEXT "+PrayerTimes.NAMES[next].toUpperCase(Locale.ROOT),false,null);
        dot(c,String.format(Locale.US,"%02d:%02d",until/60,until%60),x+13,y+42,4.3f,text);
        label(c,until>60?"until next prayer":"next prayer soon",x+13,y+89,10,dim,Paint.Align.LEFT,body);
        for(int i=0;i<6;i++) { float px=x+14+i*30; label(c,PrayerTimes.NAMES[i],px,y+111,6,dim,Paint.Align.LEFT,body);
            label(c,PrayerTimes.format(pt.minutes[i],true),px,y+127,5.5f,text,Paint.Align.LEFT,bodyMedium); }
        add("prayer_card",r,()->show(Screen.PRAYER,"prayer_city"),null);
    }

    private void drawHomeControls(Canvas c) {
        RectF input=new RectF(517,82,658,136); boolean fi=focused("home_input"); round(c,input,12,fi?focusFill():panel);
        label(c,"↪  "+primaryInputLabel(),530,105,13,fi?focusText():text,Paint.Align.LEFT,bodyMedium);
        label(c,"TV / HDMI / AV",530,125,9,fi?focusText():dim,Paint.Align.LEFT,body); add("home_input",input,()->show(Screen.INPUTS,"input0"),null);
        RectF net=new RectF(517,145,585,218); boolean fn=focused("home_network"); round(c,net,12,fn?focusFill():panel);
        label(c,networkGlyph(),551,173,22,fn?focusText():text,Paint.Align.CENTER,body); label(c,networkSummary(),551,199,9,fn?focusText():text,Paint.Align.CENTER,bodyMedium);
        add("home_network",net,()->show(Screen.NETWORK,"network_wifi"),null);
        RectF sleep=new RectF(594,145,662,218); boolean fs=focused("home_sleep"); round(c,sleep,12,fs?focusFill():danger);
        label(c,"☾",628,173,22,fs?focusText():Color.WHITE,Paint.Align.CENTER,body); label(c,"Sleep timer",628,194,9,fs?focusText():Color.WHITE,Paint.Align.CENTER,bodyMedium);
        label(c,"SYSTEM",628,207,8,fs?focusText():Color.WHITE,Paint.Align.CENTER,body); add("home_sleep",sleep,this::openSleepSettings,null);
    }

    private void drawApps(Canvas c) {
        header(c,"ALL APPS","Everything you watch, listen to and open.","02  /  APPS");
        chip(c,"filter_all",48,118,52,"All",true,()->{}); chip(c,"filter_video",106,118,62,"Video",false,()->{});
        chip(c,"filter_music",174,118,62,"Music",false,()->{}); chip(c,"filter_utils",242,118,62,"Utilities",false,()->{});
        ArrayList<App> visible=new ArrayList<>(); Set<String> hide=hidden(); for(App a:apps)if(!hide.contains(a.pkg))visible.add(a);
        boolean paged=visible.size()>18;
        label(c,visible.size()+" apps  /  Hold OK for options",paged?540:650,131,9,dim,Paint.Align.RIGHT,body);
        int start=Math.min(appPage*18,Math.max(0,visible.size()-1)); int end=Math.min(start+18,visible.size());
        for(int n=start;n<end;n++) { int i=n-start,row=i/6,col=i%6; float x=48+col*102,y=150+row*75; RectF r=new RectF(x,y,x+92,y+65); App a=visible.get(n);
            boolean f=focused("app"+i); tile(c,r,f); drawAppIcon(c,a,r.centerX(),y+25,31,false); label(c,a.label,r.centerX(),y+57,8,f?focusText():text,Paint.Align.CENTER,bodyMedium);
            add("app"+i,r,()->launch(a),()->options(a)); }
        if(visible.size()>18) { chip(c,"page",555,118,95,"Page "+(appPage+1)+" / "+((visible.size()+17)/18),false,()->{appPage=(appPage+1)%((visible.size()+17)/18);focusId="app0";invalidate();}); }
        footer(c,"02  /  APPS");
    }

    private void drawInputs(Canvas c) {
        header(c,"INPUTS","Switch sources without leaving the couch.","05  /  INPUTS");
        List<TvInputInfo> list=tvInputs();
        if(list.isEmpty()) {
            emptyState(c,"No TV inputs were published by this firmware.","Open the TV's system input picker",()->openAction("com.android.tv.action.VIEW_INPUTS"));
        } else for(int i=0;i<list.size()&&i<6;i++) { int row=i/3,col=i%3; float x=48+col*203,y=144+row*102; RectF r=new RectF(x,y,x+190,y+89); TvInputInfo in=list.get(i); int state=inputState(in);
            boolean f=focused("input"+i); tile(c,r,f); label(c,inputGlyph(in),x+18,y+29,22,f?focusText():text,Paint.Align.LEFT,body);
            label(c,safeInputLabel(in),x+14,y+57,14,f?focusText():text,Paint.Align.LEFT,bodyMedium);
            label(c,stateLabel(state),x+14,y+77,9,f?focusText():dim,Paint.Align.LEFT,body); final TvInputInfo target=in; add("input"+i,r,()->openInput(target),null);
        }
        label(c,"Only sources reported by Android TV Input Framework are shown.",48,372,10,dim,Paint.Align.LEFT,body);
        footer(c,"05  /  INPUTS");
    }

    private void drawQuick(Canvas c) {
        header(c,"QUICK SETTINGS","The controls you reach for most.","06  /  QUICK SETTINGS");
        String[] title={"Wi-Fi","Bluetooth","Input","Cast","Picture","Sound","Sleep timer","Power"};
        String[] value={networkSummary(),bluetoothState(),primaryInputLabel(),"System receiver",pictureMode(),soundMode(),"System timer","System menu"};
        String[] glyph={"⌁","ᛒ","↪","▱","▧",")))","☾","○"};
        for(int i=0;i<8;i++){int row=i/4,col=i%4;float x=48+col*151,y=142+row*94;RectF r=new RectF(x,y,x+140,y+82);boolean f=focused("quick"+i);tile(c,r,f);
            label(c,glyph[i],x+13,y+30,20,f?focusText():text,Paint.Align.LEFT,body);label(c,title[i],x+13,y+53,12,f?focusText():text,Paint.Align.LEFT,bodyMedium);label(c,value[i],x+13,y+72,9,f?focusText():dim,Paint.Align.LEFT,body);
            final int q=i;add("quick"+i,r,()->quickAction(q),null);}
        AudioManager am=(AudioManager)activity.getSystemService(Context.AUDIO_SERVICE);int vol=am.getStreamVolume(AudioManager.STREAM_MUSIC),max=am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        RectF volume=new RectF(48,332,408,363);round(c,volume,14,panel);label(c,")))  Volume",62,352,9,text,Paint.Align.LEFT,body);line(c,135,348,346,348,4,divider);line(c,135,348,135+211f*vol/Math.max(1,max),348,4,text);circle(c,135+211f*vol/Math.max(1,max),348,5,accent);label(c,String.valueOf(vol),390,352,10,text,Paint.Align.RIGHT,bodyMedium);
        RectF all=new RectF(425,332,650,363);button(c,"quick_all",all,"⚙   All settings",()->show(Screen.SETTINGS,"settings0"));footer(c,"06  /  QUICK SETTINGS");
    }

    private void drawSettings(Canvas c) {
        header(c,"SETTINGS","Make the TV feel like yours.","07  /  SETTINGS");
        String[] t={"Network & internet","Picture & sound","Remotes & accessories","Apps & storage","Home & widgets","Accessibility","Profiles & restrictions","Android system settings"};
        String[] s={networkSummary(),"Display / audio outputs",bluetoothState()+" / device pairing",apps.size()+" installed apps","Layout / appearance","Captions / reading","Accounts / parental controls","Developer options / updates / about"};
        for(int i=0;i<8;i++){int row=i/2,col=i%2;float x=48+col*311,y=139+row*61;RectF r=new RectF(x,y,x+294,y+51);boolean f=focused("settings"+i);tile(c,r,f);label(c,settingsGlyph(i),x+13,y+31,21,f?focusText():text,Paint.Align.LEFT,body);label(c,t[i],x+47,y+22,13,f?focusText():text,Paint.Align.LEFT,bodyMedium);label(c,s[i],x+47,y+39,9,f?focusText():dim,Paint.Align.LEFT,body);final int n=i;add("settings"+i,r,()->settingsAction(n),null);}footer(c,"07  /  SETTINGS");
    }

    private void drawNetwork(Canvas c) {
        header(c,"NETWORK & INTERNET","A clear connection status, at a glance.","08  /  NETWORK");
        RectF card=new RectF(48,141,270,350);round(c,card,15,panel);circle(c,159,214,42,palette.iconBackground);label(c,networkGlyph(),159,226,38,0xff171717,Paint.Align.CENTER,body);
        label(c,networkConnected()?"Connected":"Not connected",159,289,17,text,Paint.Align.CENTER,bodyMedium);label(c,networkDetail(),159,312,11,dim,Paint.Align.CENTER,body);pill(c,new RectF(108,327,210,347),networkConnected()?"ACTIVE":"OFFLINE",false,null);
        networkRow(c,"network_wifi",290,140,"Wi-Fi",wifiEnabled()?"On":"Off",()->openSystem(Settings.ACTION_WIFI_SETTINGS));
        networkRow(c,"network_current",290,195,networkDetail(),networkConnected()?"Connected":"Unavailable",()->openSystem(Settings.ACTION_WIFI_SETTINGS));
        networkRow(c,"network_other",290,250,"Other networks","Scan in system settings",()->openSystem(Settings.ACTION_WIFI_SETTINGS));
        networkRow(c,"network_ethernet",290,305,"Ethernet",isTransport(NetworkCapabilities.TRANSPORT_ETHERNET)?"Connected":"Not connected",()->openSystem(Settings.ACTION_WIRELESS_SETTINGS));
        footer(c,"08  /  NETWORK");
    }

    private void drawAccessories(Canvas c) {
        header(c,"REMOTES & ACCESSORIES","Pair a remote, headphones or a controller.","09  /  ACCESSORIES");
        RectF card=new RectF(48,141,276,350);round(c,card,15,panel);label(c,"◉",162,222,54,text,Paint.Align.CENTER,body);dot(c,"TV REMOTE",112,253,2.0f,text);label(c,"Connected through Android TV",162,291,11,dim,Paint.Align.CENTER,body);
        accessoriesRow(c,"pair",296,141,"＋  Pair accessory","Open Bluetooth pairing",()->openSystem(Settings.ACTION_BLUETOOTH_SETTINGS));
        accessoriesRow(c,"remote",296,197,"▯  TV Remote","System managed",()->openSystem(Settings.ACTION_BLUETOOTH_SETTINGS));
        accessoriesRow(c,"headphones",296,253,")))  Wireless headphones",bluetoothState(),()->openSystem(Settings.ACTION_BLUETOOTH_SETTINGS));
        accessoriesRow(c,"controller",296,309,"⌁  Game controller","Pair or manage",()->openSystem(Settings.ACTION_BLUETOOTH_SETTINGS));
        footer(c,"09  /  ACCESSORIES");
    }

    private void drawPictureSound(Canvas c) {
        header(c,"PICTURE & SOUND","Comfortable viewing. Clear listening.","10  /  PICTURE + SOUND");
        label(c,"PICTURE",48,128,9,dim,Paint.Align.LEFT,bodyMedium);label(c,"SOUND",366,128,9,dim,Paint.Align.LEFT,bodyMedium);
        String[] left={"Picture mode|"+pictureMode(),"Brightness|"+globalInt("picture_brightness",-1,"%"),"Screen size|System controlled","Advanced picture|Open controls"};
        String[] right={"Sound mode|"+soundMode(),"Audio output|TV / connected output","Digital audio|"+globalString("sound_effect","System"),"Audio delay|"+globalInt("sound_spdif_delay",-1," ms")};
        for(int i=0;i<4;i++){settingRow(c,"picture"+i,48,140+i*53,294,left[i],()->openSystem(Settings.ACTION_DISPLAY_SETTINGS));settingRow(c,"sound"+i,366,140+i*53,294,right[i],()->openSystem(Settings.ACTION_SOUND_SETTINGS));}
        label(c,"Only controls supported by the TV firmware are opened.",366,362,9,dim,Paint.Align.LEFT,body);footer(c,"10  /  PICTURE + SOUND");
    }

    private void drawAppOptions(Canvas c) {
        header(c,"APP OPTIONS","One place to launch, pin or manage an app.","03  /  APP OPTIONS"); App a=appByPackage.get(selectedPackage);if(a==null){emptyState(c,"That app is no longer installed.","Back",this::goBack);return;}
        drawAppIcon(c,a,158,213,58,false);dot(c,a.label.toUpperCase(Locale.ROOT),105,273,2.4f,text);label(c,"Installed application",158,303,11,dim,Paint.Align.CENTER,body);
        boolean pinned=favorites().stream().anyMatch(x->x.pkg.equals(a.pkg));
        appOption(c,"option_open",310,128,"▶  Open "+a.label,"",()->launch(a));
        appOption(c,"option_pin",310,176,"⚑  Pinned to Home",pinned?"On":"Off",()->toggleFavorite(a));
        appOption(c,"option_move",310,224,"↔  Move in app row","Move right",()->moveFavorite(a));
        appOption(c,"option_info",310,272,"ⓘ  App information","Storage and permissions",()->appInfo(a));
        appOption(c,"option_hide",310,320,"⠿  Hide from Home","Keep app installed",()->hideApp(a));footer(c,"03  /  APP OPTIONS");
    }

    private void drawSearch(Canvas c) {
        header(c,"SEARCH","Find an installed app, input or TV setting.","04  /  SEARCH");
        RectF bar=new RectF(48,112,650,151);round(c,bar,20,panel);label(c,"⌕",63,138,22,text,Paint.Align.LEFT,body);label(c,query.isEmpty()?"Type with the on-screen keyboard":query,92,138,17,query.isEmpty()?dim:text,Paint.Align.LEFT,body);RectF mic=new RectF(614,116,646,147);boolean fm=focused("voice");if(fm)round(c,mic,16,focusFill());label(c,"●",630,138,13,fm?focusText():text,Paint.Align.CENTER,body);add("voice",mic,()->((MainActivity)activity).startVoiceSearch(),null);
        ArrayList<App> matches=new ArrayList<>();for(App a:apps)if(!query.isEmpty()&&a.label.toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT)))matches.add(a);
        label(c,"RESULTS",48,176,9,dim,Paint.Align.LEFT,bodyMedium);for(int i=0;i<Math.min(3,matches.size());i++){App a=matches.get(i);RectF r=new RectF(48+i*126,185,164+i*126,258);boolean f=focused("result"+i);tile(c,r,f);drawAppIcon(c,a,r.centerX(),r.top+30,29,false);label(c,a.label,r.centerX(),r.bottom-8,9,f?focusText():text,Paint.Align.CENTER,body);add("result"+i,r,()->launch(a),()->options(a));}
        String keys="ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789.-_@";for(int i=0;i<keys.length();i++){int row=i/10,col=i%10;float x=48+col*39,y=262+row*24;RectF r=new RectF(x,y,x+34,y+20);boolean f=focused("key"+i);round(c,r,7,f?focusFill():panel);label(c,String.valueOf(keys.charAt(i)),r.centerX(),y+14,9,f?focusText():text,Paint.Align.CENTER,bodyMedium);final char ch=keys.charAt(i);add("key"+i,r,()->{query+=ch;invalidate();},null);}
        button(c,"search_space",455,new RectF(455,262,548,286),"Space",()->{query+=" ";invalidate();});button(c,"search_delete",557,new RectF(557,262,650,286),"Delete",()->{if(!query.isEmpty())query=query.substring(0,query.length()-1);invalidate();});button(c,"search_done",455,new RectF(455,294,650,326),"Done",()->{if(!matches.isEmpty())launch(matches.get(0));});footer(c,"04  /  SEARCH");
    }

    private void drawMedia(Canvas c) {
        header(c,"MEDIA LIBRARY","Your files, on the big screen.","12  /  LOCAL MEDIA");
        List<StorageVolume> volumes=((StorageManager)activity.getSystemService(Context.STORAGE_SERVICE)).getStorageVolumes();
        int count=Math.min(3,volumes.size());float startX=(W-(count*190f+Math.max(0,count-1)*13f))/2f-secondaryXOffset();
        int shown=0;for(StorageVolume v:volumes){if(shown>=3)break;float x=startX+shown*203;RectF r=new RectF(x,150,x+190,281);boolean f=focused("media"+shown);tile(c,r,f);label(c,v.isRemovable()?"USB":"STORAGE",x+16,177,9,f?focusText():dim,Paint.Align.LEFT,bodyMedium);label(c,"▶",r.centerX(),225,34,f?focusText():text,Paint.Align.CENTER,body);label(c,v.getDescription(activity),x+12,268,12,f?focusText():text,Paint.Align.LEFT,bodyMedium);add("media"+shown,r,this::browseMedia,null);shown++;}
        if(shown==0)emptyState(c,"No storage volume is currently mounted.","Browse with Android file picker",this::browseMedia);
        float center=360-secondaryXOffset();button(c,"browse",new RectF(center-146,316,center+146,356),"□   Browse folders",this::browseMedia);label(c,"Android's file picker is used; no media library is invented or indexed in background.",center,368,8,dim,Paint.Align.CENTER,body);footer(c,"12  /  LOCAL MEDIA");
    }

    private void drawPrayer(Canvas c) {
        header(c,"PRAYER TIMES","Choose your location and calculation settings.","15  /  PRAYER WIDGET");Calendar now=Calendar.getInstance();PrayerTimes pt=prayers(now);int next=pt.nextIndex(now),left=pt.minutesUntilNext(now);
        RectF card=new RectF(48,144,278,353);round(c,card,15,panel);label(c,"NEXT PRAYER",63,171,10,dim,Paint.Align.LEFT,body);dot(c,PrayerTimes.NAMES[next].toUpperCase(Locale.ROOT),63,190,3.2f,text);dot(c,String.format(Locale.US,"%02d:%02d",left/60,left%60),63,235,4.8f,text);label(c,left>60?"hours and minutes":"minutes remaining",63,304,10,dim,Paint.Align.LEFT,body);
        int city=prefs.getInt("prayer_city",0),method=prefs.getInt("prayer_method",0);prayerRow(c,"prayer_city",296,142,"◎  Location",PrayerTimes.CITIES[city].name,()->{prefs.edit().putInt("prayer_city",(city+1)%PrayerTimes.CITIES.length).apply();invalidate();});
        prayerRow(c,"prayer_method",296,197,"≡  Calculation method",PrayerTimes.METHODS[method],()->{prefs.edit().putInt("prayer_method",(method+1)%PrayerTimes.METHODS.length).apply();invalidate();});
        prayerRow(c,"prayer_format",296,252,"◷  Time format","24-hour",()->{});prayerRow(c,"prayer_home",296,307,"⌂  Show on Home",prefs.getBoolean("prayer_home",true)?"On":"Off",()->toggle("prayer_home",true));footer(c,"15  /  PRAYER WIDGET");
    }

    private void drawAppStorage(Canvas c) {
        header(c,"APPS & STORAGE","A lighter Home starts with fewer distractions.","16  /  APPS + STORAGE");File data=activity.getFilesDir();StatFs stat=new StatFs(data.getPath());long total=stat.getTotalBytes(),free=stat.getAvailableBytes(),used=total-free;
        RectF card=new RectF(48,144,270,353);round(c,card,15,panel);label(c,"DEVICE STORAGE",63,174,10,dim,Paint.Align.LEFT,body);dot(c,human(used),63,206,4.0f,text);label(c,"used of "+human(total),63,273,11,dim,Paint.Align.LEFT,body);line(c,63,303,247,303,7,divider);line(c,63,303,63+184f*used/Math.max(1,total),303,7,text);label(c,human(free)+" available",63,332,11,text,Paint.Align.LEFT,bodyMedium);
        appOption(c,"storage_apps",292,142,"⠿  See all apps",apps.size()+" apps",()->show(Screen.APPS,"app0"));if(!apps.isEmpty()){App a=apps.get(0);appOption(c,"storage_first",292,195,"▶  "+a.label,"App details",()->appInfo(a));}appOption(c,"storage_manage",292,248,"ⓘ  Manage applications","System settings",()->openSystem(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS));appOption(c,"storage_external",292,301,"⌁  External storage",externalStorageSummary(),this::browseMedia);footer(c,"16  /  APPS + STORAGE");
    }

    private void drawPersonalize(Canvas c) {
        header(c,"HOME & WIDGETS","Keep the things you use. Hide the rest.","14  /  PERSONALIZE");label(c,"WIDGETS",48,126,9,dim,Paint.Align.LEFT,bodyMedium);label(c,"APPEARANCE",366,126,9,dim,Paint.Align.LEFT,bodyMedium);
        personalizeRow(c,"widget_clock",48,135,"◷  Clock & date","On",()->{});personalizeRow(c,"widget_prayer",48,182,"♟  Prayer times",prefs.getBoolean("prayer_home",true)?"On":"Off",()->toggle("prayer_home",true));personalizeRow(c,"widget_sleep",48,229,"☾  Sleep timer shortcut","On",()->{});personalizeRow(c,"widget_network",48,276,"⌁  Network status","On",()->{});personalizeRow(c,"widget_input",48,323,"↪  Input shortcut","On",()->{});
        int mode=prefs.getInt("theme_mode",1),preset=prefs.getInt("theme_preset",ThemePalette.ORIGINAL);
        personalizeRow(c,"appearance_theme",366,135,"☾  Brightness mode",mode==0?"Light":mode==1?"Dark":"Automatic",()->{prefs.edit().putInt("theme_mode",(mode+1)%3).apply();invalidate();});
        personalizeRow(c,"appearance_preset",366,182,"⠿  Interface theme",ThemePalette.name(preset),()->{prefs.edit().putInt("theme_preset",ThemePalette.next(preset)).apply();backgroundKey=Integer.MIN_VALUE;invalidate();});
        personalizeRow(c,"appearance_favs",366,229,"↔  Favorite apps","Arrange in All Apps",()->show(Screen.APPS,"app0"));personalizeRow(c,"appearance_dream",366,276,"◷  Screen saver","Clock",()->openSystem(Settings.ACTION_DREAM_SETTINGS));personalizeRow(c,"appearance_motion",366,323,"≡  Motion","Reduced",()->{});footer(c,"14  /  PERSONALIZE");
    }

    private void drawLiveTv(Canvas c) {
        header(c,"LIVE TV",new SimpleDateFormat("EEEE, dd MMMM",Locale.getDefault()).format(Calendar.getInstance().getTime())+"  /  Channels from the TV provider","11  /  LIVE TV");List<TvInputInfo> inputs=tvInputs();ArrayList<TvInputInfo> tuners=new ArrayList<>();for(TvInputInfo i:inputs)if(i.getType()==TvInputInfo.TYPE_TUNER)tuners.add(i);
        chip(c,"tv_all",48,116,80,"All channels",true,()->{});button(c,"tv_scan",new RectF(552,112,650,139),"Scan channels",()->openAction("android.settings.CHANNELS_SETTINGS"));
        if(tuners.isEmpty())emptyState(c,"No tuner service is exposed by this TV.","Open the genuine Live TV app",this::openLiveTv);
        else for(int i=0;i<tuners.size()&&i<4;i++){RectF r=new RectF(48,158+i*47,650,197+i*47);boolean f=focused("tv"+i);tile(c,r,f);label(c,String.format(Locale.US,"%02d",i+1),62,r.centerY()+5,10,f?focusText():dim,Paint.Align.LEFT,body);label(c,safeInputLabel(tuners.get(i)),111,r.centerY()+5,13,f?focusText():text,Paint.Align.LEFT,bodyMedium);final TvInputInfo in=tuners.get(i);add("tv"+i,r,()->openInput(in),null);}label(c,"Programme listings come only from the TV provider; this launcher invents no channel data.",48,357,9,dim,Paint.Align.LEFT,body);footer(c,"11  /  LIVE TV");
    }

    private void drawAmbient(Canvas c) {
        dot(c,"NOTHING (TV)",43,30,1.5f,text);Calendar now=Calendar.getInstance();dot(c,new SimpleDateFormat("HH:mm",Locale.getDefault()).format(now.getTime()),76,132,6.5f,text);dot(c,new SimpleDateFormat("EEEE / dd MMMM",Locale.getDefault()).format(now.getTime()).toUpperCase(Locale.ROOT),76,225,1.8f,text);PrayerTimes pt=prayers(now);int n=pt.nextIndex(now);RectF r=new RectF(76,262,348,301);round(c,r,20,panel);circle(c,96,281,4,accent);label(c,"Next prayer  /  "+PrayerTimes.NAMES[n]+" "+PrayerTimes.format(pt.minutes[n],true),112,287,13,text,Paint.Align.LEFT,body);drawAnalog(c,520,182,92);label(c,"Press any button to return",43,375,10,dim,Paint.Align.LEFT,body);dot(c,"CLOCK / AMBIENT",565,365,1.3f,dim);add("ambient",new RectF(0,0,W,H),()->show(Screen.HOME,"fav0"),null);
    }

    // ---- Components -----------------------------------------------------
    private float secondaryXOffset(){
        switch(screen){
            case INPUTS:case MEDIA:return 14f;
            case QUICK:return 15.5f;
            case SETTINGS:return 9.5f;
            case PICTURE_SOUND:case PERSONALIZE:return 6f;
            case APPS:case APP_OPTIONS:case SEARCH:case NETWORK:case ACCESSORIES:
            case PRAYER:case APP_STORAGE:case LIVE_TV:return 11f;
            default:return 0f;
        }
    }
    private void header(Canvas c,String title,String subtitle,String page){float offset=secondaryXOffset();dot(c,"NOTHING (TV)",40-offset,27,1.0f,text);label(c,new SimpleDateFormat("HH:mm",Locale.getDefault()).format(Calendar.getInstance().getTime()),680-offset,30,8,text,Paint.Align.RIGHT,body);dot(c,title,40-offset,57,2.2f,text);label(c,subtitle,40-offset,94,9,dim,Paint.Align.LEFT,body);}
    private void footer(Canvas c,String page){float offset=-secondaryXOffset();line(c,40+offset,376,680+offset,376,1,divider);label(c,"D-PAD  Navigate    OK  Select    BACK  Return",40+offset,393,6,dim,Paint.Align.LEFT,body);dot(c,page,614+offset,384,.8f,dim);}
    private void homeBottom(Canvas c,String id,float x,String glyph,String title,Runnable run){RectF r=new RectF(x,342,x+151,370);button(c,id,r,glyph+"   "+title,run);}
    private void iconButton(Canvas c,String id,float x,String glyph,Runnable run){RectF r=new RectF(x,22,x+30,50);boolean f=focused(id);if(f)round(c,r,14,focusFill());label(c,glyph,r.centerX(),42,18,f?focusText():text,Paint.Align.CENTER,body);add(id,r,run,null);}
    private void chip(Canvas c,String id,float x,float y,float w,String value,boolean selected,Runnable run){RectF r=new RectF(x,y,x+w,y+25);boolean f=focused(id);round(c,r,13,f||selected?focusFill():panel);label(c,value,r.centerX(),y+17,9,f||selected?focusText():text,Paint.Align.CENTER,bodyMedium);add(id,r,run,null);}
    private void button(Canvas c,String id,RectF r,String title,Runnable run){boolean f=focused(id);round(c,r,14,f?focusFill():buttonFill(id));if(f)stroke(c,r,14,palette.focusText,2);label(c,title,r.centerX(),r.centerY()+5,11,f?focusText():text,Paint.Align.CENTER,bodyMedium);add(id,r,run,null);}
    private void button(Canvas c,String id,float x,RectF r,String title,Runnable run){button(c,id,r,title,run);}
    private void tile(Canvas c,RectF r,boolean focused){round(c,r,14,focused?focusFill():panel);if(focused){stroke(c,new RectF(r.left-3,r.top-3,r.right+3,r.bottom+3),16,text,2);stroke(c,r,14,palette.focusText,1);circle(c,r.right-5,r.top+5,3,danger);}}
    private void settingRow(Canvas c,String id,float x,float y,float w,String spec,Runnable run){String[] v=spec.split("\\|",2);RectF r=new RectF(x,y,x+w,y+43);boolean f=focused(id);tile(c,r,f);label(c,v[0],x+14,y+27,11,f?focusText():text,Paint.Align.LEFT,bodyMedium);if(v.length>1)label(c,v[1],x+w-12,y+27,9,f?focusText():dim,Paint.Align.RIGHT,body);add(id,r,run,null);}
    private void appOption(Canvas c,String id,float x,float y,String title,String status,Runnable run){RectF r=new RectF(x,y,650,y+43);boolean f=focused(id);tile(c,r,f);label(c,title,x+14,y+27,11,f?focusText():text,Paint.Align.LEFT,bodyMedium);label(c,status,635,y+27,9,f?focusText():dim,Paint.Align.RIGHT,body);add(id,r,run,null);}
    private void prayerRow(Canvas c,String id,float x,float y,String title,String status,Runnable run){appOption(c,id,x,y,title,status,run);}
    private void personalizeRow(Canvas c,String id,float x,float y,String title,String status,Runnable run){settingRow(c,id,x,y,294,title+"|"+status,run);}
    private void networkRow(Canvas c,String id,float x,float y,String title,String status,Runnable run){settingRow(c,id,x,y,360,"⌁  "+title+"|"+status,run);}
    private void accessoriesRow(Canvas c,String id,float x,float y,String title,String status,Runnable run){settingRow(c,id,x,y,354,title+"|"+status,run);}
    private void emptyState(Canvas c,String message,String action,Runnable run){float center=360-secondaryXOffset();label(c,message,center,225,16,text,Paint.Align.CENTER,bodyMedium);button(c,"empty_action",new RectF(center-125,255,center+125,297),action,run);}
    private void pill(Canvas c,RectF r,String value,boolean focus,Runnable run){round(c,r,r.height()/2,focus?focusFill():accent);label(c,value,r.centerX(),r.centerY()+4,7,focus?focusText():Color.WHITE,Paint.Align.CENTER,bodyMedium);if(run!=null)add("pill_"+value,r,run,null);}

    private void drawAnalog(Canvas c,float cx,float cy,float radius){circle(c,cx,cy,radius,panel);if(Color.alpha(border)>0){p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(1);p.setColor(border);c.drawCircle(cx,cy,radius,p);p.setStyle(Paint.Style.FILL);}for(int i=0;i<12;i++){double a=Math.PI*2*i/12-Math.PI/2;float x=(float)(cx+Math.cos(a)*(radius-10)),y=(float)(cy+Math.sin(a)*(radius-10));circle(c,x,y,i==0?3:1.4f,i==0?danger:dim);}Calendar now=Calendar.getInstance();double minute=now.get(Calendar.MINUTE)*Math.PI/30-Math.PI/2,hour=(now.get(Calendar.HOUR)%12+now.get(Calendar.MINUTE)/60f)*Math.PI/6-Math.PI/2;hand(c,cx,cy,hour,radius*.43f,8,text);hand(c,cx,cy,minute,radius*.62f,4,dim);circle(c,cx,cy,5,danger);}
    private void hand(Canvas c,float cx,float cy,double a,float len,float width,int color){p.setColor(color);p.setStrokeWidth(width);p.setStrokeCap(Paint.Cap.ROUND);c.drawLine(cx,cy,(float)(cx+Math.cos(a)*len),(float)(cy+Math.sin(a)*len),p);}
    private void drawAppIcon(Canvas c,App a,float cx,float cy,float size,boolean inverse){
        float radius=size/2+5;
        int iconBg=inverse?Color.WHITE:palette.iconBackground;
        if(a.icon==null){circle(c,cx,cy,radius,iconBg);label(c,a.label.substring(0,1).toUpperCase(Locale.ROOT),cx,cy+7,18,inverse?0xff171717:text,Paint.Align.CENTER,bodyMedium);return;}
        int save=c.save();
        path.reset();path.addCircle(cx,cy,radius,Path.Direction.CW);c.clipPath(path);
        circle(c,cx,cy,radius,iconBg);
        try{
            Drawable d=a.icon.mutate();if(palette.monochromeIcons)d.setColorFilter(MONO);else d.clearColorFilter();
            d.setBounds((int)(cx-radius),(int)(cy-radius),(int)(cx+radius),(int)(cy+radius));
            d.draw(c);d.clearColorFilter();
        }catch(RuntimeException ignored){}
        c.restoreToCount(save);
    }
    private int homeAppFill(int index){
        return palette.appTile(index);
    }
    private int activeNavFill(){
        return palette.preset==ThemePalette.GLASS_DARK||palette.preset==ThemePalette.INDUSTRIAL_MONO?panel:focusFill();
    }
    private int activeNavText(){
        return palette.preset==ThemePalette.GLASS_DARK||palette.preset==ThemePalette.INDUSTRIAL_MONO?text:focusText();
    }
    private int buttonFill(String id){
        if("bottom_tv".equals(id))return palette.bottomTile(0);
        if("bottom_media".equals(id))return palette.bottomTile(1);
        if("bottom_custom".equals(id))return palette.bottomTile(2);
        if("bottom_quick".equals(id))return palette.bottomTile(3);
        return panel;
    }
    private void round(Canvas c,RectF r,float radius,int color){boolean surface=color==panel||color==panel2;if(surface&&Color.alpha(palette.shadowColor())>0){p.setStyle(Paint.Style.FILL);p.setColor(palette.shadowColor());c.drawRoundRect(r.left+1.2f,r.top+1.6f,r.right+1.2f,r.bottom+1.6f,radius,radius,p);}p.setStyle(Paint.Style.FILL);p.setColor(color);c.drawRoundRect(r,radius,radius,p);if(surface&&Color.alpha(border)>0){p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(1);p.setColor(border);c.drawRoundRect(r,radius,radius,p);p.setStyle(Paint.Style.FILL);}}
    private void stroke(Canvas c,RectF r,float radius,int color,float width){p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(width);p.setColor(color);c.drawRoundRect(r,radius,radius,p);p.setStyle(Paint.Style.FILL);}
    private void circle(Canvas c,float x,float y,float radius,int color){p.setStyle(Paint.Style.FILL);p.setColor(color);c.drawCircle(x,y,radius,p);}
    private void line(Canvas c,float x1,float y1,float x2,float y2,float width,int color){p.setStrokeWidth(width);p.setStrokeCap(Paint.Cap.ROUND);p.setColor(color);c.drawLine(x1,y1,x2,y2,p);}
    private void label(Canvas c,String value,float x,float baseline,float size,int color,Paint.Align align,Typeface face){p.setTypeface(face);p.setTextSize(size);p.setTextAlign(align);p.setColor(color);p.setStyle(Paint.Style.FILL);String out=value==null?"":value;if(out.length()>64)out=out.substring(0,61)+"…";c.drawText(out,x,baseline,p);}
    private int focusFill(){return palette.focusFill;} private int focusText(){return palette.focusText;}
    private boolean focused(String id){return id.equals(focusId);} private void add(String id,RectF r,Runnable click,Runnable hold){items.add(new Item(id,new RectF(r),click,hold));}

    // Dot matrix 5x7 renderer for the reference's NDot-style display type.
    private static final Map<Character,String> DOTS=new HashMap<>();
    static {String chars="ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789:/+-.()&";String[] rows={
        "0111010001111111000110001","1111010001111101000111110","0111110000100001000001111","1111010001100011000111110","1111110000111101000011111","1111110000111101000010000","0111110000101111000101111","1000110001111111000110001","1111100100001000010011111","0011100010000101001001100","1000110010111001001010001","1000010000100001000011111","1000111011101011000110001","1000111001101011001110001","0111010001100011000101110","1111010001111101000010000","0111010001101011001001101","1111010001111101001010001","0111110000011100000111110","1111100100001000010000100","1000110001100011000101110","1000110001100010101000100","1000110001101011101110001","1000101010001000101010001","1000101010001000010000100","1111100010001000100011111",
        "0111010001100011000101110","0010001100001000010011111","0111010001000100010011111","1111000001001100000111110","0001000110010101111100010","1111110000111100000111110","0111110000111101000101110","1111100010001000100001000","0111010001011101000101110","0111010001011110000111110","0000000100000000010000000","0010000100000000010000100","0000000100001000100000000","0000000000111110000000000","0000000000000000000000100","0000100010001000100010000","0010000100000000010000100","0011001000010000100000110","0111010001011101010101111"};for(int i=0;i<chars.length();i++)DOTS.put(chars.charAt(i),rows[i]);}
    private void dot(Canvas c,String value,float x,float y,float unit,int color){p.setColor(color);p.setStyle(Paint.Style.FILL);float ox=x;String upper=(value==null?"":value.toUpperCase(Locale.ROOT));for(int k=0;k<upper.length();k++){char ch=upper.charAt(k);if(ch==' '){x+=unit*4;continue;}String bits=DOTS.get(ch);if(bits==null){label(c,String.valueOf(ch),x,y+unit*7,unit*7,color,Paint.Align.LEFT,Typeface.MONOSPACE);x+=unit*6;continue;}for(int i=0;i<35&&i<bits.length();i++)if(bits.charAt(i)=='1'){int col=i%5,row=i/5;c.drawRect(x+col*unit,y+row*unit,x+col*unit+unit*.72f,y+row*unit+unit*.72f,p);}x+=unit*6;if(x>W-20){x=ox;y+=unit*9;}}}

    // ---- Actions and real system state ---------------------------------
    private PrayerTimes prayers(Calendar now){int city=Math.max(0,Math.min(PrayerTimes.CITIES.length-1,prefs.getInt("prayer_city",0)));int method=Math.max(0,Math.min(PrayerTimes.METHODS.length-1,prefs.getInt("prayer_method",0)));return PrayerTimes.calculate(now,PrayerTimes.CITIES[city],method);}
    private void launch(App a){try{Intent i=new Intent(a.launch);i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);activity.startActivity(i);}catch(RuntimeException e){toast("App is not available");}}
    private void options(App a){selectedPackage=a.pkg;show(Screen.APP_OPTIONS,"option_open");}
    private void appInfo(App a){try{activity.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+a.pkg)));}catch(RuntimeException e){openSystem(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS);}}
    private void toggleFavorite(App a){ArrayList<String> f=favoritePackages();if(f.contains(a.pkg))f.remove(a.pkg);else{if(f.size()>=7)f.remove(f.size()-1);f.add(a.pkg);}saveFavorites(f);invalidate();}
    private void moveFavorite(App a){ArrayList<String> f=favoritePackages();int i=f.indexOf(a.pkg);if(i<0){toggleFavorite(a);return;}Collections.swap(f,i,(i+1)%f.size());saveFavorites(f);invalidate();}
    private ArrayList<String> favoritePackages(){ArrayList<String> f=new ArrayList<>();for(String pkg:prefs.getString("favorites","").split(","))if(!pkg.isEmpty())f.add(pkg);return f;}
    private void saveFavorites(List<String> f){prefs.edit().putString("favorites",TextUtils.join(",",f)).apply();}
    private void hideApp(App a){Set<String> h=hidden();h.add(a.pkg);prefs.edit().putString("hidden",TextUtils.join(",",h)).apply();show(Screen.APPS,"app0");}
    private void toggle(String key,boolean def){prefs.edit().putBoolean(key,!prefs.getBoolean(key,def)).apply();invalidate();}
    private void settingsAction(int n){switch(n){case 0:show(Screen.NETWORK,"network_wifi");break;case 1:show(Screen.PICTURE_SOUND,"picture0");break;case 2:show(Screen.ACCESSORIES,"pair");break;case 3:show(Screen.APP_STORAGE,"storage_apps");break;case 4:show(Screen.PERSONALIZE,"widget_clock");break;case 5:openSystem(Settings.ACTION_ACCESSIBILITY_SETTINGS);break;case 6:openSystem(Settings.ACTION_SYNC_SETTINGS);break;default:openSystem(Settings.ACTION_SETTINGS);}}
    private void quickAction(int n){switch(n){case 0:show(Screen.NETWORK,"network_wifi");break;case 1:show(Screen.ACCESSORIES,"pair");break;case 2:show(Screen.INPUTS,"input0");break;case 3:openSystem(Settings.ACTION_CAST_SETTINGS);break;case 4:show(Screen.PICTURE_SOUND,"picture0");break;case 5:show(Screen.PICTURE_SOUND,"sound0");break;case 6:openSleepSettings();break;default:openSystem(Settings.ACTION_SETTINGS);}}
    private void openSleepSettings(){openSystem(Settings.ACTION_DREAM_SETTINGS);toast("Sleep and power timers are controlled by TV Settings");}
    private void browseMedia(){try{Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("*/*");activity.startActivity(i);}catch(RuntimeException e){openSystem(Settings.ACTION_INTERNAL_STORAGE_SETTINGS);}}
    private void openLiveTv(){Intent i=new Intent(Intent.ACTION_VIEW, TvContract.Channels.CONTENT_URI);try{activity.startActivity(i);}catch(RuntimeException e){openAction("android.media.tv.action.SETUP_INPUTS");}}
    private void openInput(TvInputInfo info){try{Uri uri=TvContract.buildChannelUriForPassthroughInput(info.getId());Intent i=new Intent(Intent.ACTION_VIEW).setDataAndType(uri,"vnd.android.cursor.item/channel");activity.startActivity(i);}catch(RuntimeException e){openAction("com.android.tv.action.VIEW_INPUTS");}}
    private void openAction(String action){try{activity.startActivity(new Intent(action));}catch(RuntimeException e){openSystem(Settings.ACTION_SETTINGS);}}
    private void openSystem(String action){try{activity.startActivity(new Intent(action));}catch(ActivityNotFoundException|SecurityException e){try{activity.startActivity(new Intent(Settings.ACTION_SETTINGS));}catch(RuntimeException ignored){toast("This setting is unavailable on the TV");}}}
    private void toast(String s){Toast.makeText(activity,s,Toast.LENGTH_SHORT).show();}

    private List<TvInputInfo> tvInputs(){TvInputManager m=(TvInputManager)activity.getSystemService(Context.TV_INPUT_SERVICE);try{return m==null?Collections.emptyList():m.getTvInputList();}catch(RuntimeException e){return Collections.emptyList();}}
    private int inputState(TvInputInfo i){TvInputManager m=(TvInputManager)activity.getSystemService(Context.TV_INPUT_SERVICE);try{return m==null?TvInputManager.INPUT_STATE_DISCONNECTED:m.getInputState(i.getId());}catch(RuntimeException e){return TvInputManager.INPUT_STATE_DISCONNECTED;}}
    private String stateLabel(int state){return state==TvInputManager.INPUT_STATE_CONNECTED?"Connected":state==TvInputManager.INPUT_STATE_CONNECTED_STANDBY?"Standby":"Unavailable";}
    private String safeInputLabel(TvInputInfo i){try{CharSequence s=i.loadLabel(activity);return s==null?"TV input":s.toString();}catch(RuntimeException e){return "TV input";}}
    private String inputGlyph(TvInputInfo i){return i.getType()==TvInputInfo.TYPE_TUNER?"▣":i.getType()==TvInputInfo.TYPE_HDMI?"↪":"□";}
    private String settingsGlyph(int i){String[] g={"⌁","▧","◉","⠿","≡","⚝","♙","◷"};return g[Math.max(0,Math.min(g.length-1,i))];}
    private String primaryInputLabel(){for(TvInputInfo i:tvInputs())if(inputState(i)==TvInputManager.INPUT_STATE_CONNECTED)return safeInputLabel(i);return tvInputs().isEmpty()?"Inputs":"Select input";}
    private boolean networkConnected(){ConnectivityManager cm=(ConnectivityManager)activity.getSystemService(Context.CONNECTIVITY_SERVICE);try{Network n=cm.getActiveNetwork();NetworkCapabilities nc=cm.getNetworkCapabilities(n);return nc!=null&&nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);}catch(RuntimeException e){return false;}}
    private boolean isTransport(int t){ConnectivityManager cm=(ConnectivityManager)activity.getSystemService(Context.CONNECTIVITY_SERVICE);try{NetworkCapabilities nc=cm.getNetworkCapabilities(cm.getActiveNetwork());return nc!=null&&nc.hasTransport(t);}catch(RuntimeException e){return false;}}
    private boolean wifiEnabled(){try{return ((WifiManager)activity.getApplicationContext().getSystemService(Context.WIFI_SERVICE)).isWifiEnabled();}catch(RuntimeException e){return false;}}
    private String networkSummary(){if(isTransport(NetworkCapabilities.TRANSPORT_ETHERNET))return "Ethernet";if(isTransport(NetworkCapabilities.TRANSPORT_WIFI))return "Connected";return networkConnected()?"Connected":"Offline";}
    private String networkGlyph(){return isTransport(NetworkCapabilities.TRANSPORT_ETHERNET)?"↔":"⌁";}
    @SuppressWarnings("deprecation") private String networkDetail(){if(isTransport(NetworkCapabilities.TRANSPORT_ETHERNET))return "Ethernet";try{WifiInfo wi=((WifiManager)activity.getApplicationContext().getSystemService(Context.WIFI_SERVICE)).getConnectionInfo();String s=wi==null?null:wi.getSSID();if(s!=null&&!"<unknown ssid>".equalsIgnoreCase(s))return s.replace("\"","");}catch(RuntimeException ignored){}return networkConnected()?"Network connected":"No connection";}
    private String bluetoothState(){try{BluetoothAdapter a=BluetoothAdapter.getDefaultAdapter();return a!=null&&a.isEnabled()?"On":"Off";}catch(SecurityException e){return Settings.Global.getInt(activity.getContentResolver(),"bluetooth_on",0)==1?"On":"Off";}}
    private String pictureMode(){int m=Settings.Global.getInt(activity.getContentResolver(),"picture_mode",-1);return m<0?"System":"Mode "+m;}
    private String soundMode(){int m=Settings.Global.getInt(activity.getContentResolver(),"sound_style",-1);return m<0?"System":"Mode "+m;}
    private String globalString(String key,String fallback){String v=Settings.Global.getString(activity.getContentResolver(),key);return v==null?fallback:v;}
    private String globalInt(String key,int fallback,String suffix){int v=Settings.Global.getInt(activity.getContentResolver(),key,fallback);return v<0?"System":v+suffix;}
    private String externalStorageSummary(){for(StorageVolume v:((StorageManager)activity.getSystemService(Context.STORAGE_SERVICE)).getStorageVolumes())if(v.isRemovable())return v.getDescription(activity);return "No USB drive";}
    private String human(long b){float g=b/(1024f*1024f*1024f);return g>=1?String.format(Locale.US,"%.1f GB",g):String.format(Locale.US,"%.0f MB",b/(1024f*1024f));}

    @Override public boolean onKeyDown(int code,KeyEvent event){if(code==KeyEvent.KEYCODE_DPAD_CENTER||code==KeyEvent.KEYCODE_ENTER){if(event.getRepeatCount()==0){enterDown=System.currentTimeMillis();longHandled=false;}else if(!longHandled&&event.getRepeatCount()>=1){Item i=current();if(i!=null&&i.hold!=null){longHandled=true;i.hold.run();}}return true;}if(code==KeyEvent.KEYCODE_BACK){activity.onBackPressed();return true;}if(code==KeyEvent.KEYCODE_SETTINGS){openQuickSettings();return true;}if(code==KeyEvent.KEYCODE_TV_INPUT||code==KeyEvent.KEYCODE_TV){openInputs();return true;}if(code==KeyEvent.KEYCODE_PROFILE_SWITCH){openSystem(Settings.ACTION_SYNC_SETTINGS);return true;}if(screen==Screen.AMBIENT){show(Screen.HOME,"fav0");return true;}int dir=code==KeyEvent.KEYCODE_DPAD_LEFT?0:code==KeyEvent.KEYCODE_DPAD_RIGHT?1:code==KeyEvent.KEYCODE_DPAD_UP?2:code==KeyEvent.KEYCODE_DPAD_DOWN?3:-1;if(dir>=0){move(dir);return true;}return super.onKeyDown(code,event);}
    @Override public boolean onKeyUp(int code,KeyEvent event){if(code==KeyEvent.KEYCODE_DPAD_CENTER||code==KeyEvent.KEYCODE_ENTER){if(!longHandled){Item i=current();if(i!=null&&i.click!=null)i.click.run();}return true;}return super.onKeyUp(code,event);}
    private Item current(){for(Item i:items)if(i.id.equals(focusId))return i;return items.isEmpty()?null:items.get(0);}
    private void move(int dir){Item from=current();if(from==null)return;float fx=from.rect.centerX(),fy=from.rect.centerY(),best=Float.MAX_VALUE;Item target=null;for(Item i:items){if(i==from)continue;float dx=i.rect.centerX()-fx,dy=i.rect.centerY()-fy;if((dir==0&&dx>=-1)||(dir==1&&dx<=1)||(dir==2&&dy>=-1)||(dir==3&&dy<=1))continue;float main=(dir<2?Math.abs(dx):Math.abs(dy)),cross=(dir<2?Math.abs(dy):Math.abs(dx));float score=main+cross*3;if(score<best){best=score;target=i;}}if(target!=null){focusId=target.id;invalidate();}}
}

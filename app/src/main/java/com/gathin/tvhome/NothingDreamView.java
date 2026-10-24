package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Handler;
import android.view.View;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;

/** Allocation-light ambient clock matching the reference screen. */
final class NothingDreamView extends View {
    private final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Handler h=new Handler();
    private final Runnable tick=new Runnable(){@Override public void run(){invalidate();h.postDelayed(this,1000);}};
    NothingDreamView(Context c){super(c);p.setTypeface(Typeface.MONOSPACE);setBackgroundColor(0xff191919);}
    @Override protected void onAttachedToWindow(){super.onAttachedToWindow();h.post(tick);}
    @Override protected void onDetachedFromWindow(){h.removeCallbacks(tick);super.onDetachedFromWindow();}
    @Override protected void onDraw(Canvas c){super.onDraw(c);float s=Math.min(getWidth()/720f,getHeight()/405f),ox=(getWidth()-720*s)/2f,oy=(getHeight()-405*s)/2f;c.save();c.translate(ox,oy);c.scale(s,s);Calendar now=Calendar.getInstance();text(c,"NOTHING (TV)",43,36,12,0xffefefeb);text(c,new SimpleDateFormat("HH:mm",Locale.getDefault()).format(now.getTime()),76,179,58,0xfff4f3ef);text(c,new SimpleDateFormat("EEEE  /  dd MMMM",Locale.getDefault()).format(now.getTime()).toUpperCase(Locale.ROOT),76,229,16,0xffd0cfca);circle(c,520,184,92,0xff292929);for(int i=0;i<12;i++){double a=i*Math.PI/6-Math.PI/2;circle(c,(float)(520+Math.cos(a)*80),(float)(184+Math.sin(a)*80),i==0?3:1.4f,i==0?0xffe5433d:0xffaaa9a6);}double min=now.get(Calendar.MINUTE)*Math.PI/30-Math.PI/2,hr=(now.get(Calendar.HOUR)%12+now.get(Calendar.MINUTE)/60f)*Math.PI/6-Math.PI/2;hand(c,520,184,hr,44,8,0xfff2f1ed);hand(c,520,184,min,62,4,0xffaaa9a6);circle(c,520,184,5,0xffe5433d);text(c,"Press any button to return",43,376,10,0xffaaa9a6);text(c,"CLOCK / AMBIENT",565,376,9,0xffaaa9a6);c.restore();}
    private void text(Canvas c,String s,float x,float y,float size,int color){p.setTextSize(size);p.setColor(color);p.setStyle(Paint.Style.FILL);c.drawText(s,x,y,p);}
    private void circle(Canvas c,float x,float y,float r,int color){p.setColor(color);p.setStyle(Paint.Style.FILL);c.drawCircle(x,y,r,p);}
    private void hand(Canvas c,float x,float y,double a,float len,float width,int color){p.setColor(color);p.setStrokeWidth(width);p.setStrokeCap(Paint.Cap.ROUND);c.drawLine(x,y,(float)(x+Math.cos(a)*len),(float)(y+Math.sin(a)*len),p);}
}

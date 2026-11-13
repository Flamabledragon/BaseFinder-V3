package com.basefinder;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import net.minecraft.util.Util;

import java.util.Locale;

/** In-game BaseFinder client-style interface. */
public class FinderScreen extends Screen {
    private static final int PURPLE = 0xFFC65CFF;
    private static final int BG = 0xF00D0B12;
    private static final int PANEL = 0xFF17141E;
    private static final int CARD = 0xFF24202D;
    private static final int HOVER = 0xFF382747;
    private static final int MUTED = 0xFFAAA3B5;
    private static final int GREEN = 0xFF75E0A0;
    private static final int WHITE = 0xFFF5F0FA;
    private static final int RED = 0xFFFF7777;

    private enum Tab { MODULES, ESP, COORDINATES, SETTINGS }
    private Tab tab = Tab.MODULES;
    private String status = "Ready • client-loaded chunks only";
    private int selectedCluster = -1;

    public FinderScreen() { super(Text.literal("BaseFinder")); }

    private int left() { return (width - 620) / 2; }
    private int top() { return (height - 390) / 2; }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        int x=left(), y=top();
        ctx.fill(0,0,width,height,0xB9000000);
        ctx.fill(x,y,x+620,y+390,BG);
        ctx.fill(x,y,x+620,y+3,PURPLE);
        ctx.fill(x,y,x+620,y+54,PANEL);
        ctx.drawTextWithShadow(textRenderer,"BASEFINDER",x+20,y+13,PURPLE);
        ctx.drawTextWithShadow(textRenderer,"CLIENT  /  PRIVATE TEST BUILD",x+20,y+31,MUTED);
        ctx.drawTextWithShadow(textRenderer,"RIGHT SHIFT  •  CLOSE",x+470,y+21,MUTED);

        String[] tabs={"MODULES","ESP","COORDINATES","SETTINGS"};
        int[] tx={x+16,x+164,x+312,x+460};
        int[] tw={140,140,140,144};
        for(int i=0;i<tabs.length;i++) {
            boolean active=tab.ordinal()==i;
            ctx.fill(tx[i],y+62,tx[i]+tw[i],y+91,active?HOVER:CARD);
            if(active) ctx.fill(tx[i],y+62,tx[i]+tw[i],y+64,PURPLE);
            ctx.drawCenteredTextWithShadow(textRenderer,tabs[i],tx[i]+tw[i]/2,y+73,active?PURPLE:WHITE);
        }

        switch(tab) {
            case MODULES -> renderModules(ctx,x,y,mouseX,mouseY);
            case ESP -> renderEsp(ctx,x,y,mouseX,mouseY);
            case COORDINATES -> renderCoordinates(ctx,x,y,mouseX,mouseY);
            case SETTINGS -> renderSettings(ctx,x,y,mouseX,mouseY);
        }
        ctx.fill(x+16,y+356,x+604,y+380,PANEL);
        ctx.drawTextWithShadow(textRenderer,status,x+24,y+364,MUTED);
        super.render(ctx,mouseX,mouseY,delta);
    }

    private void renderModules(DrawContext c,int x,int y,int mx,int my) {
        title(c,x+20,y+108,"DETECTION MODULES");
        card(c,x+18,y+132,284,66,"Storage Finder",ChunkScanner.findStorage,"Chest • barrel • shulker • ender chest");
        card(c,x+318,y+132,284,66,"Spawner Finder",ChunkScanner.findSpawners,"Spawner block entities in loaded chunks");
        card(c,x+18,y+210,284,66,"Local auto-save",ChunkScanner.autoAlert,"Save new candidate groups to config folder");
        card(c,x+318,y+210,284,66,"Chat announcements",false,"Disabled • BaseFinder never posts to chat");
        button(c,x+18,y+292,180,34,"SCAN LOADED CHUNKS",mx,my);
        button(c,x+210,y+292,180,34,"SAVE LOCAL RESULTS",mx,my);
        button(c,x+402,y+292,200,34,"CLEAR RESULTS",mx,my);
    }

    private void renderEsp(DrawContext c,int x,int y,int mx,int my) {
        title(c,x+20,y+108,"ESP VERIFICATION");
        c.drawTextWithShadow(textRenderer,"Visual outlines are a separate renderer feature; these switches are prepared as settings.",x+20,y+132,MUTED);
        card(c,x+18,y+160,284,58,"Chest / trapped chest ESP",EspSettings.chests,"Storage block outline setting");
        card(c,x+318,y+160,284,58,"Spawner ESP",EspSettings.spawners,"Spawner outline setting");
        card(c,x+18,y+230,284,58,"Barrel / shulker ESP",EspSettings.specialStorage,"Special storage outline setting");
        card(c,x+318,y+230,284,58,"Coordinate labels",EspSettings.labels,"Show labels with an ESP renderer");
        c.drawTextWithShadow(textRenderer,"Note: toggles currently store preferences; world outlines are not yet rendered.",x+20,y+310,RED);
    }

    private void renderCoordinates(DrawContext c,int x,int y,int mx,int my) {
        title(c,x+20,y+108,"LOCAL DETECTION RESULTS");
        c.drawTextWithShadow(textRenderer,"Candidate groups found: "+ChunkScanner.clusters.size(),x+20,y+130,MUTED);
        int rowY=y+151;
        int shown=Math.min(6,ChunkScanner.clusters.size());
        for(int i=0;i<shown;i++) {
            ChunkScanner.Cluster cl=ChunkScanner.clusters.get(i);
            int ry=rowY+i*31;
            c.fill(x+18,ry,x+602,ry+27,selectedCluster==i?HOVER:CARD);
            String label=String.format(Locale.ROOT,"X %d   Y %d   Z %d    •    %.0f blocks    •    score %.0f",cl.x,cl.y,cl.z,cl.dist,cl.score);
            c.drawTextWithShadow(textRenderer,label,x+28,ry+9,WHITE);
            c.drawTextWithShadow(textRenderer,"COPY",x+550,ry+9,PURPLE);
        }
        if(shown==0) c.drawTextWithShadow(textRenderer,"No candidate groups yet. Scan loaded chunks first.",x+24,rowY+12,MUTED);
        button(c,x+18,y+345,180,30,"SCAN AGAIN",mx,my);
        button(c,x+210,y+345,180,30,"SAVE RESULTS",mx,my);
    }

    private void renderSettings(DrawContext c,int x,int y,int mx,int my) {
        title(c,x+20,y+108,"SCAN SETTINGS");
        button(c,x+18,y+145,130,34,"RADIUS  −",mx,my);
        button(c,x+158,y+145,130,34,"RADIUS  +",mx,my);
        button(c,x+318,y+145,130,34,"SCORE  −",mx,my);
        button(c,x+458,y+145,144,34,"SCORE  +",mx,my);
        c.drawTextWithShadow(textRenderer,"Radius: "+ChunkScanner.radius+" chunks",x+20,y+200,WHITE);
        c.drawTextWithShadow(textRenderer,"Minimum score: "+ChunkScanner.threshold,x+318,y+200,WHITE);
        c.drawTextWithShadow(textRenderer,"Results are based only on block entities in chunks already loaded by your client.",x+20,y+250,MUTED);
        c.drawTextWithShadow(textRenderer,"No server requests, chat announcements, or automatic coordinate sharing.",x+20,y+274,GREEN);
    }

    private void title(DrawContext c,int x,int y,String s){c.drawTextWithShadow(textRenderer,s,x,y,PURPLE);}
    private void card(DrawContext c,int x,int y,int w,int h,String name,boolean on,String sub){
        c.fill(x,y,x+w,y+h,CARD); c.fill(x,y,x+3,y+h,on?PURPLE:0xFF514B59);
        c.drawTextWithShadow(textRenderer,name,x+13,y+10,WHITE);
        c.drawTextWithShadow(textRenderer,sub,x+13,y+31,MUTED);
        c.fill(x+w-52,y+10,x+w-12,y+29,on?0xFF173B2A:0xFF3B2026);
        c.drawCenteredTextWithShadow(textRenderer,on?"ON":"OFF",x+w-32,y+16,on?GREEN:RED);
    }
    private void button(DrawContext c,int x,int y,int w,int h,String s,int mx,int my){
        boolean hover=mx>=x&&mx<=x+w&&my>=y&&my<=y+h;
        c.fill(x,y,x+w,y+h,hover?HOVER:CARD); c.fill(x,y,x+w,y+2,hover?PURPLE:0xFF51465C);
        c.drawCenteredTextWithShadow(textRenderer,s,x+w/2,y+12,WHITE);
    }
    private boolean inside(double mx,double my,int x,int y,int w,int h){return mx>=x&&mx<=x+w&&my>=y&&my<=y+h;}

    @Override public boolean mouseClicked(Click click,boolean doubled) {
        if(click.button()!=0)return super.mouseClicked(click,doubled);
        double mx=click.x(),my=click.y(); int x=left(),y=top();
        if(inside(mx,my,x+16,y+62,140,29)){tab=Tab.MODULES;return true;}
        if(inside(mx,my,x+164,y+62,140,29)){tab=Tab.ESP;return true;}
        if(inside(mx,my,x+312,y+62,140,29)){tab=Tab.COORDINATES;return true;}
        if(inside(mx,my,x+460,y+62,144,29)){tab=Tab.SETTINGS;return true;}

        if(tab==Tab.MODULES){
            if(inside(mx,my,x+18,y+132,284,66)){ChunkScanner.findStorage=!ChunkScanner.findStorage;rescan();return true;}
            if(inside(mx,my,x+318,y+132,284,66)){ChunkScanner.findSpawners=!ChunkScanner.findSpawners;rescan();return true;}
            if(inside(mx,my,x+18,y+210,284,66)){ChunkScanner.autoAlert=!ChunkScanner.autoAlert;status="Local auto-save "+(ChunkScanner.autoAlert?"enabled":"disabled");return true;}
            if(inside(mx,my,x+18,y+292,180,34)){rescan();return true;}
            if(inside(mx,my,x+210,y+292,180,34)){status=ChunkScanner.save()==null?"Local save failed":"Results saved in .minecraft/config/basefinder";return true;}
            if(inside(mx,my,x+402,y+292,200,34)){ChunkScanner.clear();status="Results cleared";return true;}
        } else if(tab==Tab.ESP){
            if(inside(mx,my,x+18,y+160,284,58)){EspSettings.chests=!EspSettings.chests;status="Chest ESP preference "+(EspSettings.chests?"enabled":"disabled");return true;}
            if(inside(mx,my,x+318,y+160,284,58)){EspSettings.spawners=!EspSettings.spawners;status="Spawner ESP preference "+(EspSettings.spawners?"enabled":"disabled");return true;}
            if(inside(mx,my,x+18,y+230,284,58)){EspSettings.specialStorage=!EspSettings.specialStorage;status="Special storage ESP preference "+(EspSettings.specialStorage?"enabled":"disabled");return true;}
            if(inside(mx,my,x+318,y+230,284,58)){EspSettings.labels=!EspSettings.labels;status="Coordinate label preference "+(EspSettings.labels?"enabled":"disabled");return true;}
        } else if(tab==Tab.COORDINATES){
            int shown=Math.min(6,ChunkScanner.clusters.size());
            for(int i=0;i<shown;i++){int ry=y+151+i*31;if(inside(mx,my,x+18,ry,584,27)){ChunkScanner.Cluster cl=ChunkScanner.clusters.get(i);MinecraftClient.getInstance().keyboard.setClipboard("X: "+cl.x+" Y: "+cl.y+" Z: "+cl.z);selectedCluster=i;status="Copied X "+cl.x+" Y "+cl.y+" Z "+cl.z;return true;}}
            if(inside(mx,my,x+18,y+345,180,30)){rescan();return true;}
            if(inside(mx,my,x+210,y+345,180,30)){status=ChunkScanner.save()==null?"Local save failed":"Results saved locally";return true;}
        } else if(tab==Tab.SETTINGS){
            if(inside(mx,my,x+18,y+145,130,34)){ChunkScanner.radius=Math.max(4,ChunkScanner.radius-4);rescan();return true;}
            if(inside(mx,my,x+158,y+145,130,34)){ChunkScanner.radius=Math.min(64,ChunkScanner.radius+4);rescan();return true;}
            if(inside(mx,my,x+318,y+145,130,34)){ChunkScanner.threshold=Math.max(2,ChunkScanner.threshold-2);rescan();return true;}
            if(inside(mx,my,x+458,y+145,144,34)){ChunkScanner.threshold=Math.min(100,ChunkScanner.threshold+2);rescan();return true;}
        }
        return super.mouseClicked(click,doubled);
    }
    private void rescan(){ChunkScanner.scan(MinecraftClient.getInstance());status="Scan complete • "+ChunkScanner.clusters.size()+" candidate groups";}
    @Override public boolean shouldPause(){return false;}
}

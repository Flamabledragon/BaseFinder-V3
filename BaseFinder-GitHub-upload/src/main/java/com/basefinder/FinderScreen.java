package com.basefinder;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Module-control style ClickGUI: top tabs, workspace sidebar, module cards with rotating 3D block banner,
 * live settings panel. Everything is drawn by hand; clicks are handled by invisible vanilla widgets that
 * are laid out by the same code that draws, so drawing and hit areas can never drift apart.
 */
public class FinderScreen extends Screen {
    private enum Tab {
        MODULES("Modules"), RESULTS("Results"), HUD("HUD"), APPEARANCE("Appearance");
        final String label;
        Tab(String label) { this.label = label; }
    }

    private record Placed(Modules.Setting s, int y, int h) {}
    private record CardPos(Modules.Module m, int x, int y) {}

    // Neutral dark palette (the accent comes from the chosen theme)
    private static final int CARD = 0xFF181B20;
    private static final int CARD_HOVER = 0xFF20242B;
    private static final int PANEL_EDGE = 0xFF2A2D33;
    private static final int SIDEBAR = 0xFF14171B;
    private static final int LINE = 0xFF22262C;
    private static final int TEXT = 0xFFEDEFF2;
    private static final int DIM = 0xFF8A9099;
    private static final int OFF = 0xFF3A3F47;

    private static final String[] SIDE = buildSide();

    private static String[] buildSide() {
        Modules.Cat[] cats = Modules.Cat.values();
        String[] out = new String[cats.length + 2];
        out[0] = "All modules";
        for (int i = 0; i < cats.length; i++) out[i + 1] = cats[i].label;
        out[out.length - 1] = "Favorites";
        return out;
    }

    // persistent UI state
    private static Tab tab = Tab.MODULES;
    private static int side = 0;
    private static boolean enabledOnly = false;
    private static String selectedId = "sound";
    private static String searchText = "";
    private static boolean searchFocus = false;
    private static int gridScroll = 0, setScroll = 0, page = 0;

    // layout
    private int W, H, px, py, sbX, sbW, ctX, ctW, rtX, rtW, bodyY, bodyB;
    private int[] tabX, tabW;
    private int cols, cardW, rowsVisible, gridY, setY0, perPage;
    private final int cardH = 38, pitch = 44;

    private int seenVersion;
    private boolean searchDirty = false;
    private EditBox search;
    private List<Modules.Module> visible = List.of();
    private List<CardPos> cardPos = new ArrayList<>();
    private List<Placed> placed = new ArrayList<>();
    private List<String> descLines = new ArrayList<>();
    private List<ChunkScanner.Cluster> view = List.of();

    public FinderScreen() {
        super(Component.literal("Base Finder"));
    }

    private static int accent() {
        return Modules.THEMES[Math.max(0, Math.min(Modules.THEMES.length - 1, Config.v("ap.theme")))];
    }

    private static int withAlpha(int argb, int a) {
        return (a << 24) | (argb & 0xFFFFFF);
    }

    // ---------------------------------------------------------------- setup
    @Override
    protected void init() {
        W = Math.min(520, this.width - 8);
        H = Math.min(284, this.height - 8);
        px = (this.width - W) / 2;
        py = (this.height - H) / 2;
        sbX = px + 8;
        sbW = 96;
        rtW = W >= 480 ? 160 : 144;
        rtX = px + W - 8 - rtW;
        ctX = sbX + sbW + 8;
        ctW = rtX - 8 - ctX;
        bodyY = py + 40;
        bodyB = py + H - 24;
        seenVersion = ChunkScanner.version;

        // top tabs (right aligned)
        Tab[] tabs = Tab.values();
        tabX = new int[tabs.length];
        tabW = new int[tabs.length];
        int x = px + W - 12;
        for (int i = tabs.length - 1; i >= 0; i--) {
            int w = this.font.width(tabs[i].label) + 18;
            x -= w;
            tabX[i] = x;
            tabW[i] = w;
            x -= 2;
        }
        for (int i = 0; i < tabs.length; i++) {
            final Tab t = tabs[i];
            hit(tabX[i], py + 8, tabW[i], 24, () -> { tab = t; setScroll = 0; page = 0; this.rebuildWidgets(); });
        }

        // sidebar
        for (int i = 0; i < SIDE.length; i++) {
            final int idx = i;
            hit(sbX + 4, bodyY + 18 + i * 22, sbW - 8, 20, () -> {
                side = idx; tab = Tab.MODULES; gridScroll = 0; this.rebuildWidgets();
            });
        }
        hit(sbX + 4, bodyB - 20, sbW - 8, 16, () -> { enabledOnly = !enabledOnly; gridScroll = 0; this.rebuildWidgets(); });

        if (tab == Tab.MODULES) initModules();
        else if (tab == Tab.RESULTS) initResults();
        else {
            List<Modules.Setting> list = tab == Tab.HUD ? Modules.HUD_SETTINGS : Modules.APPEARANCE_SETTINGS;
            int wx = ctX + 10, ww = rtX + rtW - ctX - 20, y0 = bodyY + 36;
            setScroll = Math.max(0, Math.min(setScroll, maxScroll(list, y0, bodyB - 4)));
            placed = layout(list, setScroll, y0, bodyB - 4);
            addSettingHits(wx, ww);
            scrollArrows(rtX + rtW - 34, bodyY + 6);
        }
    }

    private void initModules() {
        visible = filtered();
        cols = ctW >= 236 ? 2 : 1;
        cardW = (ctW - (cols - 1) * 8) / cols;
        gridY = bodyY + 66;
        rowsVisible = Math.max(1, (bodyB - gridY + 6) / pitch);
        int totalRows = (visible.size() + cols - 1) / cols;
        gridScroll = Math.max(0, Math.min(gridScroll, Math.max(0, totalRows - rowsVisible)));

        // search box
        if (search == null) {
            search = new EditBox(this.font, 0, 0, 10, 12, Component.empty());
            search.setBordered(false);
            search.setMaxLength(24);
            search.setResponder(s -> {
                if (!s.equals(searchText)) {
                    searchText = s;
                    gridScroll = 0;
                    searchDirty = true;
                }
            });
        }
        search.setValue(searchText);
        search.setX(ctX + 8);
        search.setY(bodyY + 47);
        search.setWidth(ctW - 56);
        this.addRenderableWidget(search);
        if (searchFocus) {
            searchFocus = false;
            this.setFocused(search);
            search.setFocused(true);
        }
        scrollArrows(ctX + ctW - 34, bodyY + 44);

        // cards: small widgets first (toggle + star), then the card itself, so the small ones win clicks
        cardPos = new ArrayList<>();
        for (int r = 0; r < rowsVisible; r++) {
            for (int c = 0; c < cols; c++) {
                int idx = (gridScroll + r) * cols + c;
                if (idx >= visible.size()) break;
                final Modules.Module m = visible.get(idx);
                int cx = ctX + c * (cardW + 8), cy = gridY + r * pitch;
                cardPos.add(new CardPos(m, cx, cy));
                hit(cx + cardW - 38, cy + 4, 34, 18, () -> { m.flip(); });
                hit(cx + cardW - 20, cy + 22, 16, 14, () -> {
                    if (!Config.favorites.remove(m.id)) Config.favorites.add(m.id);
                    Config.save();
                    this.rebuildWidgets();
                });
                hit(cx, cy, cardW, cardH, () -> { selectedId = m.id; setScroll = 0; this.rebuildWidgets(); });
            }
        }

        // settings panel of the selected module
        Modules.Module sel = Modules.byId(selectedId);
        descLines = wrap(sel.desc, rtW - 16);
        setY0 = bodyY + 52 + descLines.size() * 10 + 8;
        setScroll = Math.max(0, Math.min(setScroll, maxScroll(sel.settings, setY0, bodyB - 4)));
        placed = layout(sel.settings, setScroll, setY0, bodyB - 4);
        addSettingHits(rtX + 8, rtW - 16);
        scrollArrows(rtX + rtW - 34, bodyY + 4);
    }

    private void initResults() {
        int right = rtX + rtW;
        int ww = right - ctX;
        hit(ctX, bodyY, 76, 20, this::rescan);
        hit(ctX + 82, bodyY, 76, 20, () -> {
            String path = ChunkScanner.save();
            ChunkScanner.saveState();
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) {
                mc.player.displayClientMessage(Component.literal(path != null
                        ? "[BaseFinder] Saved to " + path : "[BaseFinder] Could not save file"), false);
            }
        });
        hit(ctX + 164, bodyY, 76, 20, () -> { ChunkScanner.resetAll(); page = 0; this.rebuildWidgets(); });
        hit(ctX + 246, bodyY, 76, 20, () -> { Config.sortByDistance = !Config.sortByDistance; page = 0; rescan(); });

        view = new ArrayList<>(ChunkScanner.clusters);
        perPage = Math.max(1, (bodyB - 20 - (bodyY + 28)) / 22);
        int pages = Math.max(1, (view.size() + perPage - 1) / perPage);
        page = Math.max(0, Math.min(page, pages - 1));

        for (int i = 0; i < perPage; i++) {
            int idx = page * perPage + i;
            if (idx >= view.size()) break;
            final ChunkScanner.Cluster c = view.get(idx);
            int y = bodyY + 28 + i * 22;
            hit(right - 84, y + 2, 38, 16, () ->
                    Minecraft.getInstance().keyboardHandler.setClipboard(c.x + " " + c.y + " " + c.z));
            hit(right - 42, y + 2, 38, 16, () -> { ChunkScanner.hide(c); this.rebuildWidgets(); });
        }
        if (page > 0) hit(ctX, bodyB - 18, 40, 16, () -> { page--; this.rebuildWidgets(); });
        if (page < pages - 1) hit(ctX + 46, bodyB - 18, 40, 16, () -> { page++; this.rebuildWidgets(); });
    }

    // ---- settings list helpers ----
    private List<Placed> layout(List<Modules.Setting> list, int scroll, int y0, int y1) {
        List<Placed> out = new ArrayList<>();
        int y = y0;
        for (int i = scroll; i < list.size(); i++) {
            Modules.Setting s = list.get(i);
            int h = s.height();
            if (y + h > y1) break;
            out.add(new Placed(s, y, h));
            y += h + 2;
        }
        return out;
    }

    private int maxScroll(List<Modules.Setting> list, int y0, int y1) {
        for (int s = 0; s < list.size(); s++) {
            int y = y0;
            boolean fits = true;
            for (int i = s; i < list.size(); i++) {
                int h = list.get(i).height();
                if (y + h > y1) { fits = false; break; }
                y += h + 2;
            }
            if (fits) return s;
        }
        return Math.max(0, list.size() - 1);
    }

    private void addSettingHits(int x, int w) {
        for (Placed p : placed) {
            final Modules.Setting s = p.s();
            switch (s.type) {
                case TOGGLE, CHOICE, BUTTON -> hit(x, p.y(), w, p.h(), s::click);
                case SLIDER -> this.addRenderableWidget(new Slide(x, p.y() + 14, w, 10, s));
                default -> { }
            }
        }
    }

    private void scrollArrows(int x, int y) {
        hit(x, y, 14, 14, () -> doScroll(-1));
        hit(x + 16, y, 14, 14, () -> doScroll(1));
    }

    private void doScroll(int d) {
        if (tab == Tab.RESULTS) page = Math.max(0, page + d);
        else setScroll = Math.max(0, setScroll + d);
        this.rebuildWidgets();
    }

    /** Mouse wheel. (No @Override on purpose: if the signature ever differs, the arrow buttons still work.) */
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        int d = sy > 0 ? -1 : 1;
        if (tab == Tab.MODULES && mx < rtX) gridScroll = Math.max(0, gridScroll + d);
        else if (tab == Tab.RESULTS) page = Math.max(0, page + d);
        else setScroll = Math.max(0, setScroll + d);
        this.rebuildWidgets();
        return true;
    }

    private List<Modules.Module> filtered() {
        List<Modules.Module> out = new ArrayList<>();
        String q = searchText.toLowerCase();
        for (Modules.Module m : Modules.ALL) {
            if (side >= 1 && side <= Modules.Cat.values().length && m.cat != Modules.Cat.values()[side - 1]) continue;
            if (side == SIDE.length - 1 && !Config.favorites.contains(m.id)) continue;
            if (enabledOnly && !m.on()) continue;
            if (!q.isEmpty() && !m.name.toLowerCase().contains(q)) continue;
            out.add(m);
        }
        return out;
    }

    private List<String> wrap(String text, int maxPx) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String word : text.split(" ")) {
            String test = cur.length() == 0 ? word : cur + " " + word;
            if (this.font.width(test) > maxPx && cur.length() > 0) {
                out.add(cur.toString());
                cur = new StringBuilder(word);
            } else {
                cur = new StringBuilder(test);
            }
        }
        if (cur.length() > 0) out.add(cur.toString());
        return out;
    }

    /** Invisible clickable area (a vanilla button with alpha 0). */
    private void hit(int x, int y, int w, int h, Runnable action) {
        Button b = Button.builder(Component.empty(), btn -> action.run()).bounds(x, y, w, h).build();
        b.setAlpha(0f);
        this.addRenderableWidget(b);
    }

    /** Vanilla slider (handles dragging for us), made invisible; the panel draws the visuals. */
    private static final class Slide extends AbstractSliderButton {
        private final Modules.Setting s;

        Slide(int x, int y, int w, int h, Modules.Setting s) {
            super(x, y, w, h, Component.empty(), (s.get.getAsInt() - s.min) / (double) (s.max - s.min));
            this.s = s;
            this.setAlpha(0f);
        }

        @Override
        protected void updateMessage() { }

        @Override
        protected void applyValue() {
            int steps = Math.max(1, s.step);
            int raw = s.min + (int) Math.round(this.value * (s.max - s.min) / steps) * steps;
            raw = Math.max(s.min, Math.min(s.max, raw));
            if (raw != s.get.getAsInt()) s.change(raw);
        }
    }

    private void rescan() {
        ChunkScanner.scan(Minecraft.getInstance());
        Config.save();
        this.rebuildWidgets();
    }

    @Override
    public void tick() {
        super.tick();
        if (Modules.pollScan()) ChunkScanner.scan(Minecraft.getInstance());
        if (Modules.pollSave()) Config.save();
        if (searchDirty) {
            searchDirty = false;
            searchFocus = true;
            this.rebuildWidgets();
            return;
        }
        if (tab == Tab.RESULTS && seenVersion != ChunkScanner.version) this.rebuildWidgets();
    }

    /** Called when the screen closes (no @Override: harmless if the hook is named differently). */
    public void removed() {
        Config.save();
    }

    // ---------------------------------------------------------------- drawing helpers
    private void rect(GuiGraphics g, int x, int y, int w, int h, int color) {
        g.fill(x, y, x + w, y + h, color);
    }

    private void text(GuiGraphics g, String s, int x, int y, int color) {
        g.drawString(this.font, s, x, y, color);
    }

    private void textR(GuiGraphics g, String s, int xRight, int y, int color) {
        g.drawString(this.font, s, xRight - this.font.width(s), y, color);
    }

    private void textC(GuiGraphics g, String s, int x, int w, int y, int color) {
        g.drawString(this.font, s, x + (w - this.font.width(s)) / 2, y, color);
    }

    private boolean over(int mx, int my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    private void border(GuiGraphics g, int x, int y, int w, int h, int color) {
        rect(g, x, y, w, 1, color);
        rect(g, x, y + h - 1, w, 1, color);
        rect(g, x, y, 1, h, color);
        rect(g, x + w - 1, y, 1, h, color);
    }

    private void pill(GuiGraphics g, int x, int y, boolean on) {
        rect(g, x, y, 24, 10, on ? accent() : OFF);
        rect(g, on ? x + 15 : x + 1, y + 1, 8, 8, 0xFFFFFFFF);
    }

    private double angle() {
        return (System.currentTimeMillis() % 3600000L) / 1000.0 * (Config.v("ap.spin") * 1.5);
    }

    // ---------------------------------------------------------------- render
    @Override
    public void render(GuiGraphics g, int mx, int my, float partialTick) {
        super.render(g, mx, my, partialTick); // background dim + (invisible) widgets
        int acc = accent();
        int bgAlpha = Math.max(0x90, Math.min(0xFF, Config.v("ap.opacity") * 255 / 100));

        // window
        rect(g, px - 1, py - 1, W + 2, H + 2, PANEL_EDGE);
        rect(g, px, py, W, H, withAlpha(0x101216, bgAlpha));

        // top bar
        rect(g, px, py, W, 38, withAlpha(0x15181D, bgAlpha));
        rect(g, px, py + 38, W, 1, PANEL_EDGE);
        CubeRenderer.draw(g, px + 24, py + 20, 6.5f, angle(), 4, Config.v("ap.style"));
        text(g, "BASEFINDER", px + 44, py + 10, acc);
        text(g, "MODULE CONTROL", px + 44, py + 21, TEXT);
        Tab[] tabs = Tab.values();
        for (int i = 0; i < tabs.length; i++) {
            boolean active = tabs[i] == tab;
            boolean hover = over(mx, my, tabX[i], py + 8, tabW[i], 24);
            if (hover && !active) rect(g, tabX[i], py + 8, tabW[i], 24, 0xFF1C2026);
            textC(g, tabs[i].label, tabX[i], tabW[i], py + 16, active ? TEXT : DIM);
            if (active) rect(g, tabX[i] + 6, py + 31, tabW[i] - 12, 2, acc);
        }

        drawSidebar(g, mx, my, acc);

        if (tab == Tab.MODULES) drawModules(g, mx, my, acc);
        else if (tab == Tab.RESULTS) drawResults(g, mx, my, acc);
        else drawWide(g, mx, my, acc);

        // footer
        int active = 0;
        for (Modules.Module m : Modules.ALL) if (m.on()) active++;
        text(g, "BASEFINDER  /  YOUR WORKSPACE", px + 12, py + H - 15, DIM);
        textR(g, active + " modules active   |   " + ChunkScanner.clusters.size() + " stash(es)", px + W - 12,
                py + H - 15, DIM);

        // EditBox must sit on top of the drawn panels
        if (tab == Tab.MODULES && search != null) search.render(g, mx, my, partialTick);

        // live preview of Sound Detector cards above the panels
        SoundDetector.renderCards(g, Minecraft.getInstance(), 0);
    }

    private void drawSidebar(GuiGraphics g, int mx, int my, int acc) {
        rect(g, sbX, bodyY, sbW, bodyB - bodyY, SIDEBAR);
        text(g, "WORKSPACE", sbX + 10, bodyY + 5, acc);
        for (int i = 0; i < SIDE.length; i++) {
            int y = bodyY + 18 + i * 22;
            boolean act = tab == Tab.MODULES && side == i;
            boolean hv = over(mx, my, sbX + 4, y, sbW - 8, 20);
            if (act) rect(g, sbX + 4, y, sbW - 8, 20, 0xFF1F242B);
            else if (hv) rect(g, sbX + 4, y, sbW - 8, 20, 0xFF191D22);
            if (act) rect(g, sbX + 4, y + 2, 2, 16, acc);
            rect(g, sbX + 14, y + 7, 6, 6, act ? acc : OFF);
            text(g, SIDE[i], sbX + 26, y + 6, act ? TEXT : DIM);
        }
        int cy = bodyB - 20;
        rect(g, sbX + 10, cy + 3, 9, 9, enabledOnly ? acc : OFF);
        if (enabledOnly) rect(g, sbX + 12, cy + 5, 5, 5, 0xFF101216);
        text(g, "Enabled only", sbX + 24, cy + 4, DIM);
    }

    private void arrows(GuiGraphics g, int mx, int my, int x, int y) {
        for (int i = 0; i < 2; i++) {
            int ax = x + i * 16;
            rect(g, ax, y, 14, 14, over(mx, my, ax, y, 14, 14) ? 0xFF2C3139 : 0xFF1E2228);
            textC(g, i == 0 ? "^" : "v", ax, 14, y + 3, DIM);
        }
    }

    // ---------------------------------------------------------------- MODULES tab
    private void drawModules(GuiGraphics g, int mx, int my, int acc) {
        // hovered card -> description in the banner ("Hover to explore")
        String hoverDesc = null;
        for (CardPos c : cardPos) {
            if (over(mx, my, c.x(), c.y(), cardW, cardH)) hoverDesc = c.m().desc;
        }

        // banner with the rotating block
        rect(g, ctX, bodyY, ctW, 40, CARD);
        border(g, ctX, bodyY, ctW, 40, LINE);
        text(g, "YOUR MODULE DESK", ctX + 10, bodyY + 5, acc);
        text(g, SIDE[side], ctX + 10, bodyY + 16, TEXT);
        String sub = hoverDesc != null ? hoverDesc : "Hover to explore. Select to configure.";
        int maxW = ctW - 80;
        while (this.font.width(sub) > maxW && sub.length() > 4) sub = sub.substring(0, sub.length() - 4) + "...";
        text(g, sub, ctX + 10, bodyY + 28, DIM);
        if (Config.on("ap.cube")) {
            CubeRenderer.draw(g, ctX + ctW - 32, bodyY + 21, 10f, angle(), 8, Config.v("ap.style"));
        }

        // search bar
        rect(g, ctX, bodyY + 44, ctW, 16, CARD);
        border(g, ctX, bodyY + 44, ctW, 16, LINE);
        if (searchText.isEmpty() && (search == null || !search.isFocused())) {
            text(g, "Search modules...", ctX + 8, bodyY + 48, DIM);
        }
        arrows(g, mx, my, ctX + ctW - 34, bodyY + 44);

        // cards
        if (visible.isEmpty()) {
            text(g, "No modules here.", ctX + 4, gridY + 6, DIM);
        }
        for (CardPos c : cardPos) {
            Modules.Module m = c.m();
            int x = c.x(), y = c.y();
            boolean on = m.on();
            boolean sel = m.id.equals(selectedId);
            rect(g, x, y, cardW, cardH, over(mx, my, x, y, cardW, cardH) ? CARD_HOVER : CARD);
            border(g, x, y, cardW, cardH, sel ? withAlpha(acc, 0xB0) : LINE);
            if (on) rect(g, x, y + 3, 2, cardH - 6, acc);
            text(g, m.name, x + 10, y + 7, TEXT);
            text(g, on ? "ACTIVE" : "INACTIVE", x + 10, y + 21, on ? acc : DIM);
            pill(g, x + cardW - 32, y + 7, on);
            boolean fav = Config.favorites.contains(m.id);
            text(g, "*", x + cardW - 14, y + 24, fav ? 0xFFFFC04D : OFF);
        }

        drawRightPanel(g, mx, my, acc);
    }

    private void drawRightPanel(GuiGraphics g, int mx, int my, int acc) {
        Modules.Module sel = Modules.byId(selectedId);
        rect(g, rtX, bodyY, rtW, bodyB - bodyY, CARD);
        border(g, rtX, bodyY, rtW, bodyB - bodyY, LINE);
        text(g, "MODULE SETTINGS", rtX + 8, bodyY + 7, DIM);
        arrows(g, mx, my, rtX + rtW - 34, bodyY + 4);
        text(g, sel.name, rtX + 8, bodyY + 22, TEXT);
        text(g, sel.cat.label, rtX + 8, bodyY + 34, acc);
        int y = bodyY + 50;
        for (String line : descLines) {
            text(g, line, rtX + 8, y, DIM);
            y += 10;
        }
        rect(g, rtX + 8, setY0 - 5, rtW - 16, 1, LINE);
        for (Placed p : placed) drawSetting(g, mx, my, p, rtX + 8, rtW - 16, acc);
    }

    private void drawSetting(GuiGraphics g, int mx, int my, Placed p, int x, int w, int acc) {
        Modules.Setting s = p.s();
        int y = p.y();
        switch (s.type) {
            case HEADER -> text(g, s.label, x, y + 5, DIM);
            case TOGGLE -> {
                text(g, s.label, x, y + 6, TEXT);
                pill(g, x + w - 26, y + 5, s.get.getAsInt() != 0);
                rect(g, x, y + p.h() - 1, w, 1, LINE);
            }
            case CHOICE -> {
                text(g, s.label, x, y + 6, TEXT);
                String v = s.display();
                int bw = this.font.width(v) + 10;
                rect(g, x + w - bw, y + 2, bw, 15, over(mx, my, x, y, w, p.h()) ? 0xFF2C3139 : 0xFF22262C);
                textC(g, v, x + w - bw, bw, y + 6, acc);
                rect(g, x, y + p.h() - 1, w, 1, LINE);
            }
            case BUTTON -> {
                boolean hv = over(mx, my, x, y, w, p.h());
                rect(g, x, y, w, p.h() - 2, hv ? 0xFF2C3139 : 0xFF22262C);
                textC(g, s.label, x, w, y + 6, TEXT);
            }
            case SLIDER -> {
                text(g, s.label, x, y + 2, TEXT);
                String v = s.display();
                int bw = this.font.width(v) + 10;
                rect(g, x + w - bw, y, bw, 12, 0xFF22262C);
                textC(g, v, x + w - bw, bw, y + 2, DIM);
                double f = (s.get.getAsInt() - s.min) / (double) Math.max(1, s.max - s.min);
                f = Math.max(0, Math.min(1, f));
                rect(g, x, y + 19, w, 3, 0xFF2A2E35);
                rect(g, x, y + 19, (int) (w * f), 3, acc);
                int kx = x + (int) (w * f), ky = y + 20;
                int[] half = {1, 2, 3, 3, 3, 2, 1};
                for (int i = 0; i < 7; i++) rect(g, kx - half[i], ky - 3 + i, half[i] * 2 + 1, 1, 0xFFFFFFFF);
            }
        }
    }

    // ---------------------------------------------------------------- HUD / APPEARANCE tabs
    private void drawWide(GuiGraphics g, int mx, int my, int acc) {
        int ww = rtX + rtW - ctX;
        rect(g, ctX, bodyY, ww, bodyB - bodyY, CARD);
        border(g, ctX, bodyY, ww, bodyB - bodyY, LINE);
        text(g, tab == Tab.HUD ? "HUD SETTINGS" : "APPEARANCE", ctX + 10, bodyY + 8, acc);
        text(g, tab == Tab.HUD ? "Everything drawn on your screen while playing."
                : "Theme, panel and the rotating 3D block.", ctX + 10, bodyY + 20, DIM);
        arrows(g, mx, my, rtX + rtW - 34, bodyY + 6);
        for (Placed p : placed) drawSetting(g, mx, my, p, ctX + 10, ww - 20, acc);

        if (tab == Tab.APPEARANCE && Config.on("ap.cube")) {
            CubeRenderer.draw(g, ctX + ww - 60, bodyB - 60, 22f, angle(), 8, Config.v("ap.style"));
        }
    }

    // ---------------------------------------------------------------- RESULTS tab
    private void drawResults(GuiGraphics g, int mx, int my, int acc) {
        int right = rtX + rtW;
        int ww = right - ctX;
        String[] labels = {"Scan now", "Save", "Reset", Config.sortByDistance ? "By dist" : "By score"};
        int[] offs = {0, 82, 164, 246};
        for (int i = 0; i < 4; i++) {
            int x = ctX + offs[i];
            rect(g, x, bodyY, 76, 20, over(mx, my, x, bodyY, 76, 20) ? 0xFF2C3139 : CARD);
            border(g, x, bodyY, 76, 20, LINE);
            textC(g, labels[i], x, 76, bodyY + 6, TEXT);
        }

        if (view.isEmpty()) {
            text(g, "Nothing found yet. Fly around, or lower the min stash score.", ctX, bodyY + 36, DIM);
            return;
        }

        int pages = Math.max(1, (view.size() + perPage - 1) / perPage);
        for (int i = 0; i < perPage; i++) {
            int idx = page * perPage + i;
            if (idx >= view.size()) break;
            ChunkScanner.Cluster c = view.get(idx);
            int y = bodyY + 28 + i * 22;
            rect(g, ctX, y, ww, 19, CARD);
            border(g, ctX, y, ww, 19, LINE);
            rect(g, ctX, y + 2, 2, 15, acc);
            text(g, "#" + (idx + 1) + "  " + Config.xyzPlain(c.x, c.y, c.z, "st.gui"), ctX + 8, y + 6, TEXT);
            String stats = c.tier() + "  " + (int) c.score + " pts  " + (int) c.dist + "m";
            textR(g, stats, right - 90, y + 6, DIM);
            smallButton(g, mx, my, right - 84, y + 2, "COPY", acc);
            smallButton(g, mx, my, right - 42, y + 2, "HIDE", acc);
        }

        int fy = bodyB - 18;
        if (page > 0) smallButton(g, mx, my, ctX, fy, "<", acc);
        if (page < pages - 1) smallButton(g, mx, my, ctX + 46, fy, ">", acc);
        text(g, "Page " + (page + 1) + "/" + pages + "   (" + view.size() + " stashes)", ctX + 94, fy + 4, DIM);
    }

    private void smallButton(GuiGraphics g, int mx, int my, int x, int y, String label, int acc) {
        int w = label.length() == 1 ? 40 : 38;
        boolean hv = over(mx, my, x, y, w, 16);
        rect(g, x, y, w, 16, hv ? withAlpha(acc, 0xC0) : 0xFF2A2E35);
        textC(g, label, x, w, y + 4, hv ? 0xFF101216 : TEXT);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}

package com.example.auction.client;

import com.example.auction.AuctionManager;
import com.example.auction.StartAuctionPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.util.*;

/** 3-step auction menu: 1) pick a block  2) amount + minimum bid  3) duration + confirm. */
public class AuctionScreen extends Screen {
    // palette
    private static final int PANEL = 0xFF171B26, PANEL2 = 0xFF202636, PANEL3 = 0xFF2A3145;
    private static final int ACCENT = 0xFF5B8CFF, ACCENT_HI = 0xFF7BA2FF;
    private static final int TEXT = 0xFFE8ECF4, MUTED = 0xFF8A93A8, WARN = 0xFFFF6B6B;
    private static final int CELL = 22;

    private static class Field {
        final boolean numeric; final int maxLen, min; int max; String text;
        Field(boolean numeric, int maxLen, String text, int min, int max) {
            this.numeric = numeric; this.maxLen = maxLen; this.text = text; this.min = min; this.max = max;
        }
        int value() { try { return Integer.parseInt(text); } catch (NumberFormatException e) { return min; } }
        int clamped() { return Math.max(min, Math.min(max, value())); }
        void set(int v) { text = String.valueOf(Math.max(min, Math.min(max, v))); }
        void normalize() { text = String.valueOf(clamped()); }
    }

    private record Hit(int x, int y, int w, int h, Runnable run) {
        boolean contains(double mx, double my) { return mx >= x && mx < x + w && my >= y && my < y + h; }
    }

    private final List<ItemStack> allBlocks = new ArrayList<>();
    private final List<String> allKeys = new ArrayList<>();
    private List<ItemStack> filtered = new ArrayList<>();
    private Item selected;
    private ItemStack selectedStack;

    private int tab = 0;
    private float tabIndicator = 0f, open = 0f, dt = 0.016f;
    private long lastNs = System.nanoTime();

    private final Field search = new Field(false, 32, "", 0, 0);
    private final Field amount = new Field(true, 2, "1", 1, 64);
    private final Field bid = new Field(true, 6, "10", 1, 100000);
    private final Field minutes = new Field(true, 4, "10", 1, 1440);
    private Field focused;
    private String lastSearch = "\u0000";

    private float scrollTarget, scrollCur;
    private final Map<String, Float> hover = new HashMap<>();
    private final List<Hit> hits = new ArrayList<>();

    // layout (recomputed every frame)
    private int px, py, pw, ph, cx, cy, cw, cb, navY;
    private int gridX, gridY, gridW, gridH, gridOffX, cols = 1;

    public AuctionScreen() {
        super(Text.translatable("screen.auctionmod.title"));
        for (Item item : Registries.ITEM) {
            if (item instanceof BlockItem && item != Items.AIR) {
                ItemStack s = new ItemStack(item);
                if (s.isEmpty()) continue;
                allBlocks.add(s);
                allKeys.add((s.getName().getString() + " " + Registries.ITEM.getId(item).getPath()).toLowerCase(Locale.ROOT));
            }
        }
        refilter();
    }

    @Override public boolean shouldPause() { return false; }

    // ---------------- helpers ----------------

    private float smooth(float k) { return 1f - (float) Math.exp(-k * dt); }

    private float hoverVal(String key, boolean hov) {
        float v = hover.getOrDefault(key, 0f);
        v += ((hov ? 1f : 0f) - v) * smooth(20f);
        hover.put(key, v);
        return v;
    }

    private static int lerp(int a, int b, float t) {
        t = Math.max(0f, Math.min(1f, t));
        int r = (int) (((a >> 16) & 255) + (((b >> 16) & 255) - ((a >> 16) & 255)) * t);
        int g = (int) (((a >> 8) & 255) + (((b >> 8) & 255) - ((a >> 8) & 255)) * t);
        int bl = (int) ((a & 255) + ((b & 255) - (a & 255)) * t);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    private static void roundRect(DrawContext c, int x, int y, int w, int h, int col) {
        c.fill(x + 2, y, x + w - 2, y + h, col);
        c.fill(x + 1, y + 1, x + w - 1, y + h - 1, col);
        c.fill(x, y + 2, x + w, y + h - 2, col);
    }

    private static float clamp(float v, float lo, float hi) { return Math.max(lo, Math.min(hi, v)); }

    private String caret(Field f) {
        return focused == f && (System.currentTimeMillis() / 500) % 2 == 0 ? "_" : "";
    }

    private static String fmtMinutes(int m) {
        if (m < 60) return m + " min";
        int h = m / 60, r = m % 60;
        return r == 0 ? h + " h" : h + " h " + r + " min";
    }

    private void layout() {
        pw = Math.min(420, width - 16);
        ph = Math.min(300, height - 16);
        px = (width - pw) / 2;
        py = (height - ph) / 2 + Math.round((1f - open) * 16f);
        cx = px + 14; cw = pw - 28;
        cy = py + 56; cb = py + ph - 46; navY = py + ph - 34;
        gridX = cx; gridY = cy + 24; gridW = cw - 8;
        gridH = Math.max(CELL, (cb - 28) - gridY);
        cols = Math.max(1, gridW / CELL);
        gridOffX = (gridW - cols * CELL) / 2;
    }

    private void refilter() {
        lastSearch = search.text;
        String q = search.text.toLowerCase(Locale.ROOT).trim();
        filtered = new ArrayList<>();
        for (int i = 0; i < allBlocks.size(); i++) {
            if (q.isEmpty() || allKeys.get(i).contains(q)) filtered.add(allBlocks.get(i));
        }
        scrollTarget = 0; scrollCur = 0;
    }

    private int rows() { return (filtered.size() + cols - 1) / cols; }
    private int maxScroll() { return Math.max(0, rows() * CELL - gridH); }

    private void setTab(int t) {
        if (focused != null && focused.numeric) focused.normalize();
        focused = null;
        tab = Math.max(0, Math.min(2, t));
    }

    private void select(ItemStack s) {
        selected = s.getItem();
        selectedStack = s;
        amount.max = Math.max(1, selected.getMaxCount());
        amount.normalize();
    }

    private void submit() {
        if (selected == null) return;
        amount.normalize(); bid.normalize(); minutes.normalize();
        ClientPlayNetworking.send(new StartAuctionPayload(
            Registries.ITEM.getId(selected), amount.clamped(), bid.clamped(), minutes.clamped()));
        close();
    }

    // ---------------- rendering ----------------

    @Override
    public void render(DrawContext ctx, int mx, int my, float delta) {
        long now = System.nanoTime();
        dt = Math.min(0.1f, (now - lastNs) / 1_000_000_000f);
        lastNs = now;
        open += (1f - open) * smooth(12f);
        hits.clear();
        layout();
        if (!search.text.equals(lastSearch)) refilter();
        scrollTarget = clamp(scrollTarget, 0, maxScroll());
        scrollCur += (scrollTarget - scrollCur) * smooth(16f);

        ctx.fill(0, 0, width, height, ((int) (140 * open)) << 24);
        roundRect(ctx, px - 1, py - 1, pw + 2, ph + 2, 0xFF0B0D14);
        roundRect(ctx, px, py, pw, ph, PANEL);

        ctx.drawText(textRenderer, "Auction House", px + 14, py + 11, TEXT, false);
        String hint = "Right Shift to close";
        ctx.drawText(textRenderer, hint, px + pw - 14 - textRenderer.getWidth(hint), py + 11, MUTED, false);

        drawTabs(ctx, mx, my);
        ItemStack tip = null;
        switch (tab) {
            case 0 -> tip = drawItemTab(ctx, mx, my);
            case 1 -> drawPriceTab(ctx, mx, my);
            default -> drawTimeTab(ctx, mx, my);
        }
        drawNav(ctx, mx, my);
        if (tip != null) ctx.drawItemTooltip(textRenderer, tip, mx, my);
    }

    private void drawTabs(DrawContext ctx, int mx, int my) {
        String[] names = {"1  Item", "2  Price", "3  Time"};
        int tw = (pw - 28) / 3;
        int ty = py + 26;
        tabIndicator += (tab * tw - tabIndicator) * smooth(16f);
        for (int i = 0; i < 3; i++) {
            int x = px + 14 + i * tw;
            boolean hov = mx >= x && mx < x + tw && my >= ty && my < ty + 20;
            float v = hoverVal("tab" + i, hov);
            int col = lerp(MUTED, TEXT, i == tab ? 1f : v * 0.8f);
            ctx.drawText(textRenderer, names[i], x + (tw - textRenderer.getWidth(names[i])) / 2, ty + 6, col, false);
            final int idx = i;
            hits.add(new Hit(x, ty, tw, 20, () -> setTab(idx)));
        }
        ctx.fill(px + 14, ty + 22, px + pw - 14, ty + 23, PANEL3);
        int ix = px + 14 + Math.round(tabIndicator);
        ctx.fill(ix + 6, ty + 21, ix + tw - 6, ty + 23, ACCENT);
    }

    private ItemStack drawItemTab(DrawContext ctx, int mx, int my) {
        // search box
        roundRect(ctx, cx, cy, cw, 18, focused == search ? ACCENT : PANEL3);
        roundRect(ctx, cx + 1, cy + 1, cw - 2, 16, PANEL2);
        String shown; int col = TEXT;
        if (search.text.isEmpty() && focused != search) { shown = "Search blocks..."; col = MUTED; }
        else shown = search.text + caret(search);
        ctx.drawText(textRenderer, shown, cx + 7, cy + 5, col, false);
        hits.add(new Hit(cx, cy, cw, 18, () -> focused = search));

        // grid
        roundRect(ctx, gridX, gridY, gridW, gridH, PANEL2);
        ItemStack hovered = null;
        boolean inGrid = mx >= gridX && mx < gridX + gridW && my >= gridY && my < gridY + gridH;
        int scroll = Math.round(scrollCur);
        ctx.enableScissor(gridX, gridY, gridX + gridW, gridY + gridH);
        int first = Math.max(0, scroll / CELL);
        int rowsVisible = gridH / CELL + 2;
        for (int r = first; r < first + rowsVisible; r++) {
            for (int c = 0; c < cols; c++) {
                int i = r * cols + c;
                if (i >= filtered.size()) break;
                ItemStack s = filtered.get(i);
                int x = gridX + gridOffX + c * CELL;
                int y = gridY + r * CELL - scroll;
                boolean hov = inGrid && mx >= x && mx < x + CELL && my >= y && my < y + CELL;
                boolean sel = selected == s.getItem();
                float v = hoverVal("c" + i, hov);
                int bg = sel ? ACCENT : lerp(PANEL2, PANEL3, v);
                if (sel || v > 0.02f) roundRect(ctx, x + 1, y + 1, CELL - 2, CELL - 2, bg);
                ctx.drawItem(s, x + 3, y + 3);
                if (hov) hovered = s;
            }
        }
        ctx.disableScissor();
        if (filtered.isEmpty()) {
            String t = "No blocks found";
            ctx.drawText(textRenderer, t, gridX + (gridW - textRenderer.getWidth(t)) / 2, gridY + gridH / 2 - 4, MUTED, false);
        }
        int max = maxScroll();
        if (max > 0) {
            int th = Math.max(16, gridH * gridH / (rows() * CELL));
            int ty = gridY + (int) ((gridH - th) * (scrollCur / max));
            roundRect(ctx, gridX + gridW + 3, gridY, 3, gridH, PANEL2);
            roundRect(ctx, gridX + gridW + 3, ty, 3, th, MUTED);
        }

        // selected strip
        roundRect(ctx, cx, cb - 22, cw, 22, PANEL2);
        if (selected == null) {
            ctx.drawText(textRenderer, "Click a block to auction it", cx + 8, cb - 15, MUTED, false);
        } else {
            ctx.drawItem(selectedStack, cx + 4, cb - 19);
            ctx.drawText(textRenderer, selectedStack.getName().getString(), cx + 26, cb - 15, TEXT, false);
        }
        return hovered;
    }

    private void drawPriceTab(DrawContext ctx, int mx, int my) {
        int have = 0;
        var player = MinecraftClient.getInstance().player;
        if (selected != null && player != null) have = AuctionManager.countItem(player, selected);
        String sub; int subCol = MUTED;
        if (selected == null) { sub = "Pick a block in step 1 first"; subCol = WARN; }
        else {
            sub = "You have " + have + " (max " + amount.max + " per auction)";
            if (have < amount.clamped()) subCol = WARN;
        }
        stepper(ctx, mx, my, cy + 2, "Amount", sub, subCol, amount);
        stepper(ctx, mx, my, cy + 66, "Minimum bid", "Starting price in diamonds", MUTED, bid);
    }

    private void drawTimeTab(DrawContext ctx, int mx, int my) {
        int[] vals = {1, 5, 10, 30, 60, 360, 1440};
        String[] labels = {"1m", "5m", "10m", "30m", "1h", "6h", "24h"};
        int gap = 4, w = (cw - gap * 6) / 7;
        ctx.drawText(textRenderer, "Quick pick", cx, cy + 2, TEXT, false);
        for (int i = 0; i < 7; i++) {
            int v = vals[i];
            boolean active = minutes.clamped() == v;
            button(ctx, mx, my, cx + i * (w + gap), cy + 16, w, 20, labels[i], true, active, () -> minutes.set(v));
        }
        stepper(ctx, mx, my, cy + 48, "Custom time", "Minutes (1 - 1440)", MUTED, minutes);

        int sy = cb - 44;
        roundRect(ctx, cx, sy, cw, 44, PANEL2);
        if (selected == null) {
            String t = "No block selected - go back to step 1";
            ctx.drawText(textRenderer, t, cx + (cw - textRenderer.getWidth(t)) / 2, sy + 18, WARN, false);
        } else {
            ctx.drawItem(selectedStack, cx + 12, sy + 14);
            ctx.drawText(textRenderer, amount.clamped() + "x " + selectedStack.getName().getString(), cx + 38, sy + 10, TEXT, false);
            ctx.drawText(textRenderer, "Min bid " + bid.clamped() + " diamonds  -  " + fmtMinutes(minutes.clamped()),
                cx + 38, sy + 24, MUTED, false);
        }
    }

    private void drawNav(DrawContext ctx, int mx, int my) {
        button(ctx, mx, my, cx, navY, 80, 22, "Back", tab > 0, false, () -> setTab(tab - 1));
        String step = "Step " + (tab + 1) + " of 3";
        ctx.drawText(textRenderer, step, cx + (cw - textRenderer.getWidth(step)) / 2, navY + 7, MUTED, false);
        if (tab < 2) {
            button(ctx, mx, my, cx + cw - 110, navY, 110, 22, "Next", tab != 0 || selected != null, true, () -> setTab(tab + 1));
        } else {
            button(ctx, mx, my, cx + cw - 110, navY, 110, 22, "Start Auction", selected != null, true, this::submit);
        }
    }

    private void button(DrawContext ctx, int mx, int my, int x, int y, int w, int h,
                        String label, boolean enabled, boolean primary, Runnable onClick) {
        boolean hov = enabled && mx >= x && mx < x + w && my >= y && my < y + h;
        float v = hoverVal(label + "@" + x + "," + (y - py), hov);
        int base = !enabled ? PANEL2 : primary ? ACCENT : PANEL3;
        int hi = primary ? ACCENT_HI : 0xFF3A4360;
        roundRect(ctx, x, y, w, h, lerp(base, hi, v));
        ctx.drawText(textRenderer, label, x + (w - textRenderer.getWidth(label)) / 2, y + (h - 8) / 2,
            enabled ? TEXT : MUTED, false);
        if (enabled) hits.add(new Hit(x, y, w, h, onClick));
    }

    private void stepper(DrawContext ctx, int mx, int my, int y, String label, String sub, int subCol, Field f) {
        ctx.drawText(textRenderer, label, cx, y, TEXT, false);
        ctx.drawText(textRenderer, sub, cx, y + 11, subCol, false);
        int bw = 34, fw = 84, gap = 4;
        int total = bw * 4 + fw + gap * 4;
        int x = cx + (cw - total) / 2;
        int by = y + 26;
        int fx = x + 2 * (bw + gap);
        int[] steps = {-10, -1, 1, 10};
        for (int i = 0; i < 4; i++) {
            int bx = i < 2 ? x + i * (bw + gap) : fx + fw + gap + (i - 2) * (bw + gap);
            int s = steps[i];
            button(ctx, mx, my, bx, by, bw, 20, (s > 0 ? "+" : "") + s, true, false, () -> f.set(f.clamped() + s));
        }
        roundRect(ctx, fx, by, fw, 20, focused == f ? ACCENT : PANEL3);
        roundRect(ctx, fx + 1, by + 1, fw - 2, 18, PANEL2);
        String t = f.text + caret(f);
        ctx.drawText(textRenderer, t, fx + (fw - textRenderer.getWidth(t)) / 2, by + 6, TEXT, false);
        hits.add(new Hit(fx, by, fw, 20, () -> focused = f));
    }

    // ---------------- input ----------------

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button != 0) return super.mouseClicked(mx, my, button);
        Field prev = focused;
        focused = null;
        if (prev != null && prev.numeric) prev.normalize();
        for (int i = hits.size() - 1; i >= 0; i--) {
            Hit h = hits.get(i);
            if (h.contains(mx, my)) { h.run().run(); return true; }
        }
        if (tab == 0 && mx >= gridX && mx < gridX + gridW && my >= gridY && my < gridY + gridH) {
            double lx = mx - gridX - gridOffX;
            if (lx >= 0) {
                int c = (int) (lx / CELL);
                int r = (int) ((my - gridY + Math.round(scrollCur)) / CELL);
                int i = r * cols + c;
                if (c < cols && i >= 0 && i < filtered.size()) select(filtered.get(i));
            }
        }
        return true;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double hAmount, double vAmount) {
        if (tab == 0 && mx >= gridX && mx < gridX + gridW && my >= gridY && my < gridY + gridH) {
            scrollTarget = clamp(scrollTarget - (float) vAmount * CELL * 2f, 0, maxScroll());
            return true;
        }
        return false;
    }

    @Override
    public boolean charTyped(char chr, int modifiers) {
        if (focused == null) return false;
        boolean ok = focused.numeric ? (chr >= '0' && chr <= '9') : (chr >= 32 && chr != 127);
        if (ok && focused.text.length() < focused.maxLen) focused.text += chr;
        return ok;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (focused != null) {
            if (keyCode == GLFW.GLFW_KEY_BACKSPACE) {
                if (!focused.text.isEmpty()) focused.text = focused.text.substring(0, focused.text.length() - 1);
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER || keyCode == GLFW.GLFW_KEY_ESCAPE) {
                if (focused.numeric) focused.normalize();
                focused = null;
                return true;
            }
            return true; // swallow other keys while typing (e.g. shift)
        }
        if (keyCode == GLFW.GLFW_KEY_RIGHT_SHIFT) { close(); return true; }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }
}

package com.seolwoni.skyblocklab.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

import com.seolwoni.skyblocklab.Config;
import com.seolwoni.skyblocklab.gfs.AutoGfs;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** SkyBlock Lab settings (Right Shift or Mod Menu). Main buttons sit at the very top, like the Assist mods. */
public class ConfigScreen extends Screen {
	private static final String[] TABS = {"ESP", "터미널", "GFS"};
	private static final int ROW = 24;

	private static int tab = 0;
	private final Screen parent;
	private final List<Object[]> labels = new ArrayList<>();

	public ConfigScreen(Screen parent) {
		super(Component.literal("SkyBlock Lab 설정"));
		this.parent = parent;
	}

	private Config c() {
		return Config.get();
	}

	@Override
	protected void init() {
		labels.clear();
		int cx = this.width / 2, left = cx - 155, right = cx + 5;

		addRenderableWidget(Button.builder(toggleText("모드 전체", c().modEnabled), b -> {
			c().modEnabled = !c().modEnabled;
			Config.save();
			rebuildWidgets();
		}).bounds(left, 22, 100, 20).build());
		Button gfs = Button.builder(Component.literal("GFS 채우기").withColor(0xFF77FF77), b -> {
			AutoGfs.refillNow(this.minecraft);
			this.minecraft.gui.setScreen(null);
		}).bounds(left + 105, 22, 100, 20).build();
		gfs.active = c().modEnabled && c().gfsEnabled && this.minecraft.player != null;
		addRenderableWidget(gfs);
		addRenderableWidget(Button.builder(Component.literal("완료"), b -> onClose())
				.bounds(left + 262, 22, 48, 20).build());

		int tw = (310 - 4) / TABS.length;
		for (int i = 0; i < TABS.length; i++) {
			final int idx = i;
			Button tb = Button.builder(Component.literal(TABS[i]), b -> {
				tab = idx;
				rebuildWidgets();
			}).bounds(left + i * (tw + 2), 46, tw, 20).build();
			tb.active = i != tab;
			addRenderableWidget(tb);
		}

		int y = 74;
		switch (tab) {
			case 0 -> esp(left, right, y);
			case 1 -> terminals(left, right, y);
			default -> gfs(left, right, y);
		}
	}

	// ------------------------------------------------------------------ tabs

	private void esp(int left, int right, int y) {
		Config c = c();
		toggle(left, y, 150, "ESP", () -> c.espEnabled, v -> c.espEnabled = v);
		slider(right, y, 150, "거리", "칸", 8, 256, c.espRange, v -> c.espRange = v);
		y += ROW;
		toggle(left, y, 150, "스타 몹 (✯)", () -> c.espStarMobs, v -> c.espStarMobs = v);
		toggle(right, y, 150, "박쥐", () -> c.espBats, v -> c.espBats = v);
		y += ROW;
		toggle(left, y, 150, "위더 (F7)", () -> c.espWithers, v -> c.espWithers = v);
		toggle(right, y, 150, "플레이어", () -> c.espPlayers, v -> c.espPlayers = v);
		y += ROW;
		toggle(left, y, 150, "모든 몹", () -> c.espAllMobs, v -> c.espAllMobs = v);
		y += ROW;
		note("벽 너머로 외곽선이 보여요 · 이름 필터는 config/skyblocklab.json 의 espNames", y);
	}

	private void terminals(int left, int right, int y) {
		Config c = c();
		toggle(left, y, 150, "자동 터미널", () -> c.termEnabled, v -> c.termEnabled = v);
		toggle(right, y, 150, "가운데 클릭", () -> c.termMiddleClick, v -> c.termMiddleClick = v);
		y += ROW;
		toggle(left, y, 150, "순서대로", () -> c.termOrder, v -> c.termOrder = v);
		toggle(right, y, 150, "색상 고르기", () -> c.termColors, v -> c.termColors = v);
		y += ROW;
		toggle(left, y, 150, "시작 글자", () -> c.termStartsWith, v -> c.termStartsWith = v);
		toggle(right, y, 150, "패널 맞추기", () -> c.termPanes, v -> c.termPanes = v);
		y += ROW;
		toggle(left, y, 150, "루빅스", () -> c.termRubix, v -> c.termRubix = v);
		toggle(right, y, 150, "멜로디", () -> c.termMelody, v -> c.termMelody = v);
		y += ROW;
		slider(left, y, 150, "첫 클릭 대기", "ms", 0, 2000, c.termFirstDelay, v -> c.termFirstDelay = v);
		slider(right, y, 150, "클릭 간격", "ms", 50, 1000, c.termClickDelay, v -> c.termClickDelay = v);
		y += ROW;
		slider(left, y, 150, "재시도 대기", "ms", 200, 3000, c.termResendAfter, v -> c.termResendAfter = v);
		labels.add(new Object[] {"응답 없는 클릭을 다시 보내기까지", right, y + 6, 0xFFAAAAAA, false});
		y += ROW;
		note("가운데 클릭 = 아이템을 집지 않고 클릭 · 루빅스 반대 방향은 우클릭", y);
	}

	private void gfs(int left, int right, int y) {
		Config c = c();
		toggle(left, y, 150, "자동 GFS", () -> c.gfsEnabled, v -> c.gfsEnabled = v);
		toggle(right, y, 150, "던전 시작 시", () -> c.gfsOnDungeonStart, v -> c.gfsOnDungeonStart = v);
		y += ROW;
		slider(left, y, 150, "주기", "초", 0, 600, c.gfsIntervalSec, v -> c.gfsIntervalSec = v);
		labels.add(new Object[] {"0초 = 주기적으로 채우지 않음", right, y + 6, 0xFFAAAAAA, false});
		y += ROW;
		toggle(left, y, 150, "엔더 진주", () -> c.gfsPearls, v -> c.gfsPearls = v);
		slider(right, y, 150, "목표", "개", 1, 16, c.gfsPearlTarget, v -> c.gfsPearlTarget = v);
		y += ROW;
		toggle(left, y, 150, "스피릿 립", () -> c.gfsLeaps, v -> c.gfsLeaps = v);
		slider(right, y, 150, "목표", "개", 1, 64, c.gfsLeapTarget, v -> c.gfsLeapTarget = v);
		y += ROW;
		toggle(left, y, 150, "슈퍼붐 TNT", () -> c.gfsSuperboom, v -> c.gfsSuperboom = v);
		slider(right, y, 150, "목표", "개", 1, 64, c.gfsSuperboomTarget, v -> c.gfsSuperboomTarget = v);
		y += ROW;
		toggle(left, y, 150, "디코이", () -> c.gfsDecoy, v -> c.gfsDecoy = v);
		slider(right, y, 150, "목표", "개", 1, 64, c.gfsDecoyTarget, v -> c.gfsDecoyTarget = v);
		y += ROW;
		note("모자란 만큼만 /gfs 로 가방에서 꺼내요 · 조작 설정에서 바로 채우기 키 지정", y);
	}

	// ------------------------------------------------------------------ helpers

	private void slider(int x, int y, int w, String label, String unit, int min, int max, int cur, Consumer<Integer> set) {
		addRenderableWidget(new IntSlider(x, y, w, label, unit, min, max, cur, v -> {
			set.accept(v);
			Config.save();
		}));
	}

	private void toggle(int x, int y, int w, String label, Supplier<Boolean> get, Consumer<Boolean> set) {
		addRenderableWidget(Button.builder(toggleText(label, get.get()), b -> {
			boolean v = !get.get();
			set.accept(v);
			Config.save();
			b.setMessage(toggleText(label, v));
		}).bounds(x, y, w, 20).build());
	}

	private static Component toggleText(String label, boolean on) {
		return Component.literal(label + ": ").append(on
				? Component.literal("켜짐").withColor(0xFF55FF55)
				: Component.literal("꺼짐").withColor(0xFFFF5555));
	}

	private void note(String text, int y) {
		labels.add(new Object[] {text, this.width / 2, y, 0xFFAAAAAA, true});
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
		super.extractRenderState(g, mx, my, delta);
		g.centeredText(this.font, this.title, this.width / 2, 8, 0xFFFFFFFF);
		for (Object[] l : labels) {
			if ((boolean) l[4]) g.centeredText(this.font, Component.literal((String) l[0]), (int) l[1], (int) l[2], (int) l[3]);
			else g.text(this.font, Component.literal((String) l[0]).getVisualOrderText(), (int) l[1], (int) l[2], (int) l[3], true);
		}
	}

	@Override
	public void onClose() {
		Config.save();
		this.minecraft.gui.setScreen(parent);
	}
}

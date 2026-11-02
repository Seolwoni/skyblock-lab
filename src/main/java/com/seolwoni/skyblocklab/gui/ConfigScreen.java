package com.seolwoni.skyblocklab.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

import com.seolwoni.skyblocklab.Config;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Settings screen (Right Shift). Three tabs: ESP, terminals, GFS. */
public class ConfigScreen extends Screen {
	private static int tab = 0;
	private final Screen parent;
	private final List<Object[]> labels = new ArrayList<>();

	public ConfigScreen(Screen parent) {
		super(Component.literal("SkyBlock Lab"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		labels.clear();
		int cx = this.width / 2;
		String[] tabs = {"ESP", "자동 터미널", "자동 GFS"};
		for (int i = 0; i < tabs.length; i++) {
			int t = i;
			Button b = Button.builder(Component.literal(tabs[i]), x -> {
				tab = t;
				rebuildWidgets();
			}).bounds(cx - 150 + i * 102, 22, 96, 20).build();
			b.active = tab != i;
			addRenderableWidget(b);
		}

		Config c = Config.get();
		Row r = new Row(cx - 150, 50);
		switch (tab) {
			case 0 -> {
				r.toggle("ESP 켜기", () -> c.espEnabled, v -> c.espEnabled = v);
				r.toggle("스타 몹 (✯)", () -> c.espStarMobs, v -> c.espStarMobs = v);
				r.toggle("박쥐", () -> c.espBats, v -> c.espBats = v);
				r.toggle("위더 (F7 보스)", () -> c.espWithers, v -> c.espWithers = v);
				r.toggle("플레이어", () -> c.espPlayers, v -> c.espPlayers = v);
				r.toggle("모든 몹", () -> c.espAllMobs, v -> c.espAllMobs = v);
				r.number("거리", "칸", 8, 256, 8, () -> c.espRange, v -> c.espRange = v);
				r.note("이름 필터는 config/skyblocklab.json 의 espNames 에 추가");
			}
			case 1 -> {
				r.toggle("자동 터미널 켜기", () -> c.termEnabled, v -> c.termEnabled = v);
				r.toggle("순서대로 (Click in order)", () -> c.termOrder, v -> c.termOrder = v);
				r.toggle("색상 고르기 (Select all)", () -> c.termColors, v -> c.termColors = v);
				r.toggle("시작 글자 (Starts with)", () -> c.termStartsWith, v -> c.termStartsWith = v);
				r.toggle("패널 맞추기 (Correct panes)", () -> c.termPanes, v -> c.termPanes = v);
				r.toggle("루빅스 (Same color)", () -> c.termRubix, v -> c.termRubix = v);
				r.toggle("멜로디 (On time)", () -> c.termMelody, v -> c.termMelody = v);
				r.toggle("가운데 클릭 사용", () -> c.termMiddleClick, v -> c.termMiddleClick = v);
				r.number("첫 클릭 대기", "ms", 0, 2000, 50, () -> c.termFirstDelay, v -> c.termFirstDelay = v);
				r.number("클릭 간격", "ms", 50, 1000, 10, () -> c.termClickDelay, v -> c.termClickDelay = v);
				r.number("재시도 대기", "ms", 200, 3000, 100, () -> c.termResendAfter, v -> c.termResendAfter = v);
			}
			default -> {
				r.toggle("자동 GFS 켜기", () -> c.gfsEnabled, v -> c.gfsEnabled = v);
				r.toggle("던전 시작 시 채우기", () -> c.gfsOnDungeonStart, v -> c.gfsOnDungeonStart = v);
				r.number("주기적으로 채우기 (0=끔)", "초", 0, 600, 10, () -> c.gfsIntervalSec, v -> c.gfsIntervalSec = v);
				r.toggle("엔더 진주", () -> c.gfsPearls, v -> c.gfsPearls = v);
				r.number("  목표 개수", "개", 1, 16, 1, () -> c.gfsPearlTarget, v -> c.gfsPearlTarget = v);
				r.toggle("스피릿 립", () -> c.gfsLeaps, v -> c.gfsLeaps = v);
				r.number("  목표 개수", "개", 1, 64, 1, () -> c.gfsLeapTarget, v -> c.gfsLeapTarget = v);
				r.toggle("슈퍼붐 TNT", () -> c.gfsSuperboom, v -> c.gfsSuperboom = v);
				r.number("  목표 개수", "개", 1, 64, 4, () -> c.gfsSuperboomTarget, v -> c.gfsSuperboomTarget = v);
				r.toggle("디코이", () -> c.gfsDecoy, v -> c.gfsDecoy = v);
				r.number("  목표 개수", "개", 1, 64, 4, () -> c.gfsDecoyTarget, v -> c.gfsDecoyTarget = v);
				r.note("지금 바로 채우기: 조작 설정에서 키 지정");
			}
		}

		addRenderableWidget(Button.builder(Component.literal("완료"), b -> onClose())
				.bounds(cx - 75, this.height - 26, 150, 20).build());
	}

	/** Lays out one setting per line. */
	private class Row {
		private final int x;
		private int y;

		Row(int x, int y) {
			this.x = x;
			this.y = y;
		}

		void toggle(String name, BooleanSupplier get, Consumer<Boolean> set) {
			boolean on = get.getAsBoolean();
			addRenderableWidget(Button.builder(Component.literal(name + ": " + (on ? "켜짐" : "꺼짐")).withColor(on ? 0xFF55FF55 : 0xFFFF5555), b -> {
				set.accept(!get.getAsBoolean());
				Config.save();
				rebuildWidgets();
			}).bounds(x, y, 300, 20).build());
			y += 22;
		}

		void number(String name, String unit, int min, int max, int step, IntSupplier get, IntConsumer set) {
			addRenderableWidget(Button.builder(Component.literal("-"), b -> {
				set.accept(Math.max(min, get.getAsInt() - step));
				Config.save();
				rebuildWidgets();
			}).bounds(x + 230, y, 30, 20).build());
			addRenderableWidget(Button.builder(Component.literal("+"), b -> {
				set.accept(Math.min(max, get.getAsInt() + step));
				Config.save();
				rebuildWidgets();
			}).bounds(x + 270, y, 30, 20).build());
			labels.add(new Object[] {name + ": " + get.getAsInt() + unit, x + 4, y + 6});
			y += 22;
		}

		void note(String text) {
			labels.add(new Object[] {text, x + 4, y + 6});
			y += 22;
		}
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
		super.extractRenderState(g, mx, my, delta);
		g.centeredText(this.font, this.title, this.width / 2, 8, 0xFFFFFFFF);
		for (Object[] l : labels) g.text(this.font, Component.literal((String) l[0]).getVisualOrderText(), (int) l[1], (int) l[2], 0xFFDDDDDD, true);
	}

	@Override
	public void onClose() {
		Config.save();
		this.minecraft.gui.setScreen(parent);
	}
}

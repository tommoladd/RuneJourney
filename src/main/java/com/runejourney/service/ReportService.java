package com.runejourney.service;

import com.runejourney.model.DayRecord;
import com.runejourney.model.GoalType;
import com.runejourney.model.JourneyEvent;
import com.runejourney.planner.Counters;
import com.runejourney.planner.GoalProgress;
import com.runejourney.planner.Skills;
import com.runejourney.report.Analytics;
import com.runejourney.report.Granularity;
import com.runejourney.report.Metric;
import com.runejourney.report.SvgCharts;
import com.runejourney.report.Unit;
import com.runejourney.util.Format;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import javax.imageio.ImageIO;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Skill;

/**
 * Builds shareable reports of an account's progress over a period: a standalone HTML page and a
 * short Discord-friendly text summary.
 */
@Slf4j
@Singleton
public class ReportService
{
	private static final DateTimeFormatter EVENT_TIME = DateTimeFormatter.ofPattern("EEE d MMM, HH:mm", Locale.ENGLISH);
	private static final DateTimeFormatter GENERATED = DateTimeFormatter.ofPattern("d MMM yyyy 'at' HH:mm", Locale.ENGLISH);
	private static final int MAX_SCREENSHOTS = 12;
	private static final int SCREENSHOT_WIDTH = 640;

	private final JourneyService service;
	private final JourneyStore store;

	@Inject
	public ReportService(JourneyService service, JourneyStore store)
	{
		this.service = service;
		this.store = store;
	}

	/**
	 * The metric being explored in the reports window, shown as its own section of the export.
	 */
	@lombok.Value
	public static class Focus
	{
		Metric metric;
		String filter;
		String filterLabel;
		Granularity granularity;
		boolean line;
		boolean compare;
	}

	@Data
	public static class Report
	{
		private List<DayRecord> days = new ArrayList<>();
		private List<DayRecord> previousDays = new ArrayList<>();
		private Focus focus;
		private String title;
		private String player;
		private String profileKey;
		private LocalDate from;
		private LocalDate to;
		private LocalDateTime generated;
		private RangeSummary summary;
		private List<GoalProgress> goals;
		private Map<String, Double> minutesPerKill = new LinkedHashMap<>();
	}

	/**
	 * Gathers report data. Cheap; can run on any thread.
	 */
	public Report build(String title, LocalDate from, LocalDate to)
	{
		Report r = new Report();
		r.setTitle(title);
		r.setPlayer(service.playerName());
		r.setProfileKey(service.getProfileKey());
		r.setFrom(from);
		r.setTo(to);
		r.setGenerated(LocalDateTime.now());
		r.setSummary(service.summarize(from, to));
		r.setGoals(service.goalProgress());
		for (String boss : r.getSummary().getBossKillsByName().keySet())
		{
			r.getMinutesPerKill().put(boss, service.minutesPerKill(boss));
		}
		LocalDate[] prev = Analytics.previousPeriod(from, to);
		for (DayRecord d : service.daysBetween(prev[0], to))
		{
			(LocalDate.parse(d.getDate()).isBefore(from) ? r.getPreviousDays() : r.getDays()).add(d);
		}
		return r;
	}

	// ------------------------------------------------------------------
	// Text
	// ------------------------------------------------------------------

	public String toText(Report r)
	{
		RangeSummary s = r.getSummary();
		StringBuilder sb = new StringBuilder();
		sb.append("**RuneJourney").append(r.getPlayer() != null ? " · " + r.getPlayer() : "").append(" — ")
			.append(r.getTitle()).append("**\n");
		sb.append(period(r)).append("\n\n");

		sb.append("⏱ ").append(Format.duration(s.getPlayMillis())).append(" played · ⭐ ")
			.append(Format.compact(s.getXpGained())).append(" XP · 🏆 ").append(s.getLevelsGained()).append(" levels\n");
		sb.append("💰 ").append(Format.compact(s.getLootValue() + s.getSkillingIncome())).append(" income");
		if (s.getSkillingIncome() != 0)
		{
			sb.append(" (").append(Format.compact(s.getSkillingIncome())).append(" from skilling)");
		}
		sb.append(" · ⚔ ")
			.append(Format.number(s.getBossKills())).append(" boss kills");
		if (s.getCluesCompleted() > 0)
		{
			sb.append(" · 📜 ").append(s.getCluesCompleted()).append(" clues");
		}
		sb.append("\n");
		List<String> extra = new ArrayList<>();
		if (s.getCollectionLogSlots() > 0)
		{
			extra.add("📖 " + s.getCollectionLogSlots() + " collection log");
		}
		if (s.getQuestsCompleted() > 0)
		{
			extra.add("🗺 " + s.getQuestsCompleted() + " quests");
		}
		if (s.getCombatTasks() > 0)
		{
			extra.add("🗡 " + s.getCombatTasks() + " combat tasks (+" + s.getCombatTaskPoints() + " pts)");
		}
		if (s.getPets() > 0)
		{
			extra.add("🐾 " + s.getPets() + " pet" + (s.getPets() == 1 ? "" : "s"));
		}
		if (!extra.isEmpty())
		{
			sb.append(String.join(" · ", extra)).append("\n");
		}

		if (!s.getLevelRanges().isEmpty())
		{
			sb.append("\n**Levels:** ").append(s.getLevelRanges().entrySet().stream()
				.sorted((a, b) -> b.getValue()[1] - a.getValue()[1])
				.limit(8)
				.map(e -> e.getValue()[0] + "→" + e.getValue()[1] + " " + skillName(e.getKey()))
				.collect(Collectors.joining(", "))).append("\n");
		}
		if (!s.getBossKillsByName().isEmpty())
		{
			sb.append("**Bossing:** ").append(s.getBossKillsByName().entrySet().stream()
				.sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
				.limit(6)
				.map(e -> e.getKey() + " ×" + e.getValue())
				.collect(Collectors.joining(", "))).append("\n");
		}
		if (!s.getDrops().isEmpty())
		{
			sb.append("**Best drops:** ").append(s.getDrops().stream().limit(3)
				.map(e -> e.getTitle() + " (" + Format.compact(e.getValue()) + ")")
				.collect(Collectors.joining(", "))).append("\n");
		}
		if (!s.getCollectionLogItems().isEmpty())
		{
			sb.append("**New log slots:** ").append(String.join(", ", limit(s.getCollectionLogItems(), 8))).append("\n");
		}
		if (!s.getGoalsCompleted().isEmpty())
		{
			sb.append("**Goals achieved:** ").append(String.join(", ", s.getGoalsCompleted())).append("\n");
		}

		List<GoalProgress> active = r.getGoals().stream().filter(g -> !g.isComplete()).collect(Collectors.toList());
		if (!active.isEmpty())
		{
			sb.append("\n**Goals**\n");
			for (GoalProgress g : active)
			{
				sb.append("• ").append(g.getGoal().getName());
				if (g.getGoal().getType() != GoalType.CUSTOM)
				{
					sb.append(" — ").append(Format.percent(g.getPercent()));
				}
				if (g.getStatus() != null && g.getStatus() != GoalProgress.Status.NO_TARGET && g.getStatus() != GoalProgress.Status.TRACKING)
				{
					sb.append(" (").append(g.getStatus().getLabel().toLowerCase(Locale.ENGLISH)).append(")");
				}
				sb.append("\n");
			}
		}
		return sb.toString();
	}

	private static List<String> limit(List<String> list, int n)
	{
		if (list.size() <= n)
		{
			return list;
		}
		List<String> out = new ArrayList<>(list.subList(0, n));
		out.add("+" + (list.size() - n) + " more");
		return out;
	}

	private static String skillName(String key)
	{
		Skill s = Skills.parse(key);
		return s == null ? key : s.getName();
	}

	private static String period(Report r)
	{
		if (r.getFrom().equals(r.getTo()))
		{
			return Format.date(r.getFrom());
		}
		return Format.date(r.getFrom()) + " – " + Format.date(r.getTo());
	}

	// ------------------------------------------------------------------
	// HTML
	// ------------------------------------------------------------------

	/**
	 * Renders the report as a standalone HTML page. Reads screenshots from disk, so it must not run
	 * on the client thread.
	 */
	public String toHtml(Report r, boolean includeScreenshots)
	{
		RangeSummary s = r.getSummary();
		StringBuilder h = new StringBuilder(64 * 1024);
		h.append("<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">")
			.append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
			.append("<title>").append(esc("RuneJourney · " + (r.getPlayer() != null ? r.getPlayer() + " · " : "") + r.getTitle())).append("</title>")
			.append("<style>").append(CSS).append("</style></head><body><main>");

		// Header
		h.append("<header><div class=\"brand\">RuneJourney</div><h1>")
			.append(esc(r.getPlayer() != null ? r.getPlayer() : "Your account")).append("</h1><p class=\"sub\">")
			.append(esc(r.getTitle())).append(" · ").append(esc(period(r))).append("</p></header>");

		// Headline tiles, with the change from the previous period of the same length
		h.append("<section class=\"tiles\">");
		Metric[] headline = {Metric.PLAYTIME, Metric.XP, Metric.LEVELS, Metric.INCOME, Metric.KILLS, Metric.CLUES,
			Metric.COLLECTION_LOG, Metric.QUESTS, Metric.COMBAT_TASKS, Metric.PERSONAL_BESTS, Metric.PETS, Metric.DEATHS};
		for (Metric m : headline)
		{
			Analytics.Stats st = Analytics.stats(r.getDays(), r.getFrom(), r.getTo(), m, null, Granularity.DAY, r.getPreviousDays());
			double change = st.change();
			String delta = Double.isNaN(change) ? null : (change >= 0 ? "+" : "") + String.format(Locale.ENGLISH, "%.0f%%", change * 100);
			tile(h, m.getLabel(), m.getUnit().format(st.getTotal()), delta, m != Metric.DEATHS ? change >= 0 : change <= 0);
		}
		h.append("</section>");

		activity(h, r);
		focus(h, r);
		accountProgress(h, s);
		skills(h, s);
		bossing(h, r);
		loot(h, s);
		achievements(h, s);
		goals(h, r);
		timeline(h, r, includeScreenshots);

		h.append("<footer>Generated by RuneJourney on ").append(esc(r.getGenerated().format(GENERATED)))
			.append(". Days played: ").append(s.getDaysPlayed()).append(".</footer>");
		h.append("</main></body></html>");
		return h.toString();
	}

	private static void tile(StringBuilder h, String label, String value, String delta, boolean good)
	{
		h.append("<div class=\"tile\"><div class=\"v\">").append(esc(value)).append("</div><div class=\"l\">")
			.append(esc(label)).append("</div>");
		if (delta != null)
		{
			h.append("<div class=\"d ").append(good ? "good" : "bad").append("\">").append(esc(delta)).append(" vs previous</div>");
		}
		h.append("</div>");
	}

	/**
	 * Charts of the main metrics over the period.
	 */
	private static void activity(StringBuilder h, Report r)
	{
		if (r.getDays().isEmpty())
		{
			return;
		}
		Granularity g = Granularity.AUTO.resolve(r.getFrom(), r.getTo());
		h.append("<section><h2>Activity by ").append(g.noun()).append("</h2><div class=\"charts\">");
		Object[][] charts = {
			{Metric.XP, "#e0b040"}, {Metric.PLAYTIME, "#4fc3f7"}, {Metric.INCOME, "#66bb6a"}, {Metric.KILLS, "#ef5350"},
		};
		for (Object[] c : charts)
		{
			Metric m = (Metric) c[0];
			List<Analytics.Bucket> series = Analytics.series(r.getDays(), r.getFrom(), r.getTo(), m, null, g);
			List<Analytics.Bucket> prev = Analytics.previousSeries(r.getPreviousDays(), r.getFrom(), r.getTo(), m, null, g);
			double total = series.stream().mapToDouble(Analytics.Bucket::getValue).sum();
			h.append("<div><h3>").append(esc(m.getLabel())).append(" <span class=\"muted\">").append(esc(m.getUnit().format(total)))
				.append("</span></h3>").append(SvgCharts.timeSeries(series, r.getPreviousDays().isEmpty() ? null : prev, m.getUnit(), (String) c[1], false))
				.append("</div>");
		}
		h.append("</div>");
		if (!r.getPreviousDays().isEmpty())
		{
			long length = java.time.temporal.ChronoUnit.DAYS.between(r.getFrom(), r.getTo()) + 1;
			h.append("<p class=\"muted small\">Dashed line: the previous ").append(length).append(length == 1 ? " day" : " days").append(".</p>");
		}

		List<Analytics.Entry> weekday = Analytics.byWeekday(r.getDays(), r.getFrom(), r.getTo(), Metric.PLAYTIME, null);
		h.append("<div class=\"cols\"><div><h3>Average play time by day of week</h3>")
			.append(SvgCharts.bars(weekday, Unit.HOURS, "#4fc3f7", 7)).append("</div>");

		List<Analytics.Entry> income = new ArrayList<>();
		double loot = 0;
		double clue = 0;
		for (DayRecord d : r.getDays())
		{
			loot += d.getLootValue() - d.getClueLootValue();
			clue += d.getClueLootValue();
		}
		if (loot > 0)
		{
			income.add(new Analytics.Entry("loot", "Drops & loot", loot));
		}
		if (clue > 0)
		{
			income.add(new Analytics.Entry("clues", "Clue caskets", clue));
		}
		for (Analytics.Entry e : Analytics.breakdown(r.getDays(), r.getFrom(), r.getTo(), Metric.SKILLING_INCOME))
		{
			if (e.getValue() > 0)
			{
				income.add(new Analytics.Entry(e.getKey(), skillName(e.getKey()), e.getValue()));
			}
		}
		income.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
		if (!income.isEmpty())
		{
			h.append("<div><h3>Income by source</h3>").append(SvgCharts.bars(income, Unit.GP, "#66bb6a", 10)).append("</div>");
		}
		h.append("</div></section>");
	}

	/**
	 * The metric the player was exploring when they exported.
	 */
	private static void focus(StringBuilder h, Report r)
	{
		Focus f = r.getFocus();
		if (f == null)
		{
			return;
		}
		Metric m = f.getMetric();
		Granularity g = f.getGranularity().resolve(r.getFrom(), r.getTo());
		List<Analytics.Bucket> series = Analytics.series(r.getDays(), r.getFrom(), r.getTo(), m, f.getFilter(), g);
		List<Analytics.Bucket> prev = f.isCompare() && !r.getPreviousDays().isEmpty()
			? Analytics.previousSeries(r.getPreviousDays(), r.getFrom(), r.getTo(), m, f.getFilter(), g) : null;
		Analytics.Stats st = Analytics.stats(r.getDays(), r.getFrom(), r.getTo(), m, f.getFilter(), g, r.getPreviousDays());

		h.append("<section><h2>Focus: ").append(esc(m.getLabel())).append(f.getFilterLabel() != null ? " · " + esc(f.getFilterLabel()) : "")
			.append("</h2><div class=\"stats\">");
		stat(h, m.isPerHour() ? "Overall rate" : "Total", m.getUnit().format(st.getTotal()));
		if (!m.isPerHour())
		{
			stat(h, "Per day played", m.getUnit().format(st.getPerActiveDay()));
		}
		if (st.getBest() != null)
		{
			stat(h, "Best " + g.noun(), m.getUnit().format(st.getBest().getValue()) + " (" + st.getBest().getLabel() + ")");
		}
		if (!Double.isNaN(st.change()))
		{
			stat(h, "Vs previous period", (st.change() >= 0 ? "+" : "") + String.format(Locale.ENGLISH, "%.0f%%", st.change() * 100));
		}
		h.append("</div>").append(SvgCharts.timeSeries(series, prev, m.getUnit(), "#e0b040", f.isLine()));

		h.append("<div class=\"cols\">");
		if (m.getFilter() != Metric.Filter.NONE && f.getFilter() == null && !m.isPerHour())
		{
			List<Analytics.Entry> parts = new ArrayList<>();
			for (Analytics.Entry e : Analytics.breakdown(r.getDays(), r.getFrom(), r.getTo(), m))
			{
				parts.add(new Analytics.Entry(e.getKey(), skillName(e.getKey()), e.getValue()));
			}
			h.append("<div><h3>Breakdown</h3>").append(SvgCharts.bars(parts, m.getUnit(), "#e0b040", 15)).append("</div>");
		}
		h.append("<div><h3>By day of week</h3>")
			.append(SvgCharts.bars(Analytics.byWeekday(r.getDays(), r.getFrom(), r.getTo(), m, f.getFilter()), m.getUnit(), "#e0b040", 7))
			.append("</div></div>");

		h.append("<details><summary>Data table</summary><table><tr><th>").append(esc(g.getLabel())).append("</th><th>")
			.append(esc(m.getLabel())).append("</th><th>Hours played</th></tr>");
		for (Analytics.Bucket b : series)
		{
			h.append("<tr><td>").append(esc(b.getLabel())).append("</td><td>").append(esc(m.getUnit().format(b.getValue())))
				.append("</td><td>").append(esc(Format.hours(b.getHours()))).append("</td></tr>");
		}
		h.append("</table></details></section>");
	}

	private static void stat(StringBuilder h, String label, String value)
	{
		h.append("<div><div class=\"l\">").append(esc(label)).append("</div><div class=\"v\">").append(esc(value)).append("</div></div>");
	}

	private static void accountProgress(StringBuilder h, RangeSummary s)
	{
		Map<String, Long> a = s.getStartSnapshot();
		Map<String, Long> b = s.getEndSnapshot();
		if (a.isEmpty() || b.isEmpty())
		{
			return;
		}
		List<String[]> rows = new ArrayList<>();
		rows.add(new String[]{"Total level", String.valueOf(Skills.totalLevel(a)), String.valueOf(Skills.totalLevel(b)),
			signed(Skills.totalLevel(b) - Skills.totalLevel(a))});
		long xpA = totalXp(a);
		long xpB = totalXp(b);
		rows.add(new String[]{"Total XP", Format.compact(xpA), Format.compact(xpB), "+" + Format.compact(xpB - xpA)});
		counterRow(rows, "Quest points", Counters.QUEST_POINTS, a, b);
		counterRow(rows, "Collection log", Counters.COLLECTION_LOG, a, b);
		counterRow(rows, "CA points", Counters.CA_POINTS, a, b);
		counterRow(rows, "Clues completed", Counters.clues(Counters.ALL_TIERS), a, b);
		Long wa = a.get(Counters.WEALTH);
		Long wb = b.get(Counters.WEALTH);
		if (wa != null && wb != null)
		{
			rows.add(new String[]{"Net worth", Format.compact(wa), Format.compact(wb), (wb >= wa ? "+" : "-") + Format.compact(Math.abs(wb - wa))});
		}

		h.append("<section><h2>Account progress</h2><table><tr><th></th><th>Start</th><th>End</th><th>Change</th></tr>");
		for (String[] row : rows)
		{
			h.append("<tr><td>").append(esc(row[0])).append("</td><td>").append(esc(row[1])).append("</td><td>")
				.append(esc(row[2])).append("</td><td class=\"up\">").append(esc(row[3])).append("</td></tr>");
		}
		h.append("</table></section>");
	}

	private static void counterRow(List<String[]> rows, String label, String key, Map<String, Long> a, Map<String, Long> b)
	{
		Long va = a.get(key);
		Long vb = b.get(key);
		if (va == null || vb == null || (va == 0 && vb == 0))
		{
			return;
		}
		rows.add(new String[]{label, Format.number(va), Format.number(vb), signed(vb - va)});
	}

	private static String signed(long v)
	{
		return (v >= 0 ? "+" : "") + Format.number(v);
	}

	private static long totalXp(Map<String, Long> snapshot)
	{
		long total = 0;
		for (Skill s : Skills.ALL)
		{
			total += Skills.xp(snapshot, s);
		}
		return total;
	}

	private static void skills(StringBuilder h, RangeSummary s)
	{
		if (s.getSkillXp().isEmpty())
		{
			return;
		}
		long max = s.getSkillXp().values().stream().mapToLong(Long::longValue).max().orElse(1);
		h.append("<section><h2>Skills</h2><table><tr><th>Skill</th><th>Levels</th><th>XP gained</th><th class=\"barcol\"></th></tr>");
		s.getSkillXp().entrySet().stream()
			.sorted(Map.Entry.<String, Long>comparingByValue().reversed())
			.forEach(e ->
			{
				int[] range = s.getLevelRanges().get(e.getKey());
				String levels = range != null ? range[0] + " → " + range[1] : "";
				if (range == null && !s.getEndSnapshot().isEmpty())
				{
					Skill skill = Skills.parse(e.getKey());
					levels = skill == null ? "" : String.valueOf(Skills.level(Skills.xp(s.getEndSnapshot(), skill)));
				}
				h.append("<tr><td>").append(esc(skillName(e.getKey()))).append("</td><td>").append(esc(levels))
					.append("</td><td>").append(Format.number(e.getValue())).append("</td><td class=\"barcol\"><div class=\"bar\"><span style=\"width:")
					.append(Math.max(1, Math.round(e.getValue() * 100.0 / max))).append("%\"></span></div></td></tr>");
			});
		h.append("</table></section>");
	}

	private static void bossing(StringBuilder h, Report r)
	{
		RangeSummary s = r.getSummary();
		if (s.getBossKillsByName().isEmpty())
		{
			return;
		}
		Map<String, Long> end = s.getEndSnapshot();
		h.append("<section><h2>Bossing & activities</h2><table><tr><th>Boss</th><th>Kills</th><th>KC now</th><th>Time (est.)</th></tr>");
		s.getBossKillsByName().entrySet().stream()
			.sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
			.forEach(e ->
			{
				Long kc = end.get(Counters.kc(e.getKey()));
				double mins = r.getMinutesPerKill().getOrDefault(e.getKey(), -1d);
				h.append("<tr><td>").append(esc(e.getKey())).append("</td><td>").append(Format.number(e.getValue()))
					.append("</td><td>").append(kc != null ? Format.number(kc) : "").append("</td><td>")
					.append(mins > 0 ? esc(Format.hours(e.getValue() * mins / 60)) : "").append("</td></tr>");
			});
		h.append("</table></section>");
	}

	private static void loot(StringBuilder h, RangeSummary s)
	{
		if (s.getLootValue() <= 0 && s.getCluesCompleted() == 0)
		{
			return;
		}
		h.append("<section><h2>Loot</h2><div class=\"cols\"><div>");
		h.append("<p class=\"big\">").append(esc(Format.compact(s.getLootValue()))).append(" gp</p><p class=\"muted\">total loot");
		if (s.getClueLootValue() > 0)
		{
			h.append(", including ").append(esc(Format.compact(s.getClueLootValue()))).append(" from clues");
		}
		h.append("</p>");
		if (!s.getClues().isEmpty())
		{
			h.append("<h3>Clue scrolls</h3><ul>");
			for (String tier : Counters.CLUE_TIERS)
			{
				Integer n = s.getClues().get(tier);
				if (n != null)
				{
					h.append("<li>").append(esc(tier)).append(": ").append(n).append("</li>");
				}
			}
			h.append("</ul>");
		}
		h.append("</div><div>");
		if (!s.getDrops().isEmpty())
		{
			h.append("<h3>Best drops</h3><ol>");
			for (JourneyEvent e : s.getDrops().subList(0, Math.min(10, s.getDrops().size())))
			{
				h.append("<li><b>").append(esc(e.getTitle())).append("</b><span class=\"muted\"> · ")
					.append(esc(e.getDetail() != null ? e.getDetail() : "")).append("</span></li>");
			}
			h.append("</ol>");
		}
		h.append("</div></div></section>");
	}

	private static void achievements(StringBuilder h, RangeSummary s)
	{
		boolean any = !s.getCollectionLogItems().isEmpty() || !s.getQuests().isEmpty() || !s.getDiaries().isEmpty()
			|| !s.getCombatTaskNames().isEmpty() || !s.getPersonalBestList().isEmpty() || !s.getPetList().isEmpty();
		if (!any)
		{
			return;
		}
		h.append("<section><h2>Achievements</h2><div class=\"cols\">");
		list(h, "Collection log", s.getCollectionLogItems());
		list(h, "Pets", s.getPetList());
		list(h, "Quests", s.getQuests());
		list(h, "Achievement diaries", s.getDiaries());
		list(h, "Combat tasks", s.getCombatTaskNames());
		list(h, "Personal bests", s.getPersonalBestList());
		h.append("</div></section>");
	}

	private static void list(StringBuilder h, String title, List<String> items)
	{
		if (items.isEmpty())
		{
			return;
		}
		h.append("<div><h3>").append(esc(title)).append(" (").append(items.size()).append(")</h3><ul>");
		for (String item : items)
		{
			h.append("<li>").append(esc(item)).append("</li>");
		}
		h.append("</ul></div>");
	}

	private static void goals(StringBuilder h, Report r)
	{
		List<GoalProgress> goals = r.getGoals();
		RangeSummary s = r.getSummary();
		if (goals.isEmpty() && s.getGoalsCompleted().isEmpty())
		{
			return;
		}
		h.append("<section><h2>Goals</h2>");
		if (!s.getGoalsCompleted().isEmpty())
		{
			h.append("<p class=\"good\">Achieved this period: ").append(esc(String.join(", ", s.getGoalsCompleted()))).append("</p>");
		}
		h.append("<div class=\"goals\">");
		for (GoalProgress g : goals)
		{
			if (g.isComplete())
			{
				continue;
			}
			h.append("<div class=\"goal\"><div class=\"gh\"><b>").append(esc(g.getGoal().getName())).append("</b>");
			if (g.getStatus() != null && g.getGoal().getType() != GoalType.CUSTOM)
			{
				h.append("<span class=\"status s-").append(g.getStatus().name().toLowerCase(Locale.ENGLISH)).append("\">")
					.append(esc(g.getStatus().getLabel())).append("</span>");
			}
			h.append("</div>");
			if (g.getGoal().getType() != GoalType.CUSTOM)
			{
				h.append("<div class=\"bar\"><span style=\"width:").append(Math.round(g.getPercent() * 100)).append("%\"></span></div>")
					.append("<p class=\"muted\">").append(esc(Format.percent(g.getPercent()))).append(" complete");
				if (g.getHoursRemaining() > 0)
				{
					h.append(" · ~").append(esc(Format.hours(g.getHoursRemaining()))).append(" to go");
				}
				if (g.getTargetDate() != null)
				{
					h.append(" · target ").append(esc(Format.date(g.getTargetDate())));
				}
				if (g.getProjectedCompletion() != null)
				{
					h.append(" · estimated ").append(esc(Format.date(g.getProjectedCompletion())));
				}
				h.append("</p>");
			}
			h.append("</div>");
		}
		h.append("</div></section>");
	}

	private void timeline(StringBuilder h, Report r, boolean includeScreenshots)
	{
		List<JourneyEvent> highlights = r.getSummary().getHighlights();
		if (highlights.isEmpty())
		{
			return;
		}
		h.append("<section><h2>Highlights</h2><div class=\"timeline\">");
		int shots = 0;
		for (JourneyEvent e : highlights)
		{
			String time = Instant.ofEpochMilli(e.getTime()).atZone(ZoneId.systemDefault()).format(EVENT_TIME);
			String color = String.format("#%06x", e.getType().getColor().getRGB() & 0xFFFFFF);
			h.append("<div class=\"event\" style=\"border-color:").append(color).append("\"><div class=\"et\" style=\"color:")
				.append(color).append("\">").append(esc(e.getType().getLabel())).append(" · ").append(esc(time))
				.append("</div><div class=\"title\">").append(esc(e.getTitle())).append("</div>");
			if (e.getDetail() != null)
			{
				h.append("<div class=\"muted\">").append(esc(e.getDetail())).append("</div>");
			}
			if (e.getNote() != null)
			{
				h.append("<div class=\"note\">").append(esc(e.getNote()).replace("\n", "<br>")).append("</div>");
			}
			if (includeScreenshots && e.getScreenshot() != null && shots < MAX_SCREENSHOTS && r.getProfileKey() != null)
			{
				String data = screenshot(r.getProfileKey(), e.getScreenshot());
				if (data != null)
				{
					h.append("<img alt=\"").append(esc(e.getTitle())).append("\" src=\"").append(data).append("\">");
					shots++;
				}
			}
			h.append("</div>");
		}
		h.append("</div></section>");
	}

	/**
	 * A screenshot scaled down and encoded as a JPEG data URI, or null if it can't be read.
	 */
	private String screenshot(String profileKey, String name)
	{
		try
		{
			BufferedImage img = store.readScreenshot(profileKey, name);
			if (img == null)
			{
				return null;
			}
			int w = Math.min(SCREENSHOT_WIDTH, img.getWidth());
			int hgt = img.getHeight() * w / img.getWidth();
			BufferedImage scaled = new BufferedImage(w, hgt, BufferedImage.TYPE_INT_RGB);
			Graphics2D g = scaled.createGraphics();
			g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
			g.drawImage(img, 0, 0, w, hgt, null);
			g.dispose();
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			ImageIO.write(scaled, "jpg", out);
			return "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(out.toByteArray());
		}
		catch (IOException e)
		{
			log.debug("Unable to embed screenshot {}", name, e);
			return null;
		}
	}

	private static String esc(String s)
	{
		if (s == null)
		{
			return "";
		}
		return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
	}

	private static final String CSS = ""
		+ ":root{--bg:#15130f;--card:#1f1b15;--line:#3a3226;--text:#ece4d4;--muted:#a79d8b;--gold:#e0b040;--good:#5cbf60;--warn:#ffa726;--bad:#ef5350}"
		+ "*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--text);font:15px/1.5 system-ui,-apple-system,Segoe UI,Roboto,sans-serif}"
		+ "main{max-width:980px;margin:0 auto;padding:32px 16px 48px}"
		+ "header{margin-bottom:24px}.brand{color:var(--gold);letter-spacing:.2em;text-transform:uppercase;font-size:12px;font-weight:700}"
		+ "h1{margin:4px 0 0;font-size:32px}.sub{margin:4px 0 0;color:var(--muted)}"
		+ "section{background:var(--card);border:1px solid var(--line);border-radius:12px;padding:18px 20px;margin:16px 0}"
		+ "h2{margin:0 0 12px;color:var(--gold);font-size:14px;letter-spacing:.12em;text-transform:uppercase}"
		+ "h3{margin:12px 0 6px;font-size:14px}"
		+ ".tiles{display:grid;grid-template-columns:repeat(auto-fill,minmax(140px,1fr));gap:10px;background:none;border:0;padding:0}"
		+ ".tile{background:var(--card);border:1px solid var(--line);border-radius:12px;padding:14px}"
		+ ".tile .v{font-size:22px;font-weight:700}.tile .l{color:var(--muted);font-size:12px;text-transform:uppercase;letter-spacing:.08em}"
		+ "table{width:100%;border-collapse:collapse}th,td{text-align:left;padding:6px 8px;border-bottom:1px solid var(--line)}"
		+ "th{color:var(--muted);font-weight:600;font-size:12px;text-transform:uppercase}td.up{color:var(--good)}"
		+ ".barcol{width:35%}.bar{height:8px;background:#2b261e;border-radius:4px;overflow:hidden}.bar span{display:block;height:100%;background:var(--gold)}"
		+ ".cols{display:grid;grid-template-columns:repeat(auto-fit,minmax(260px,1fr));gap:12px 24px}"
		+ "ul,ol{margin:0;padding-left:20px}.muted{color:var(--muted)}.note{color:#d7ccc8;font-style:italic;margin-top:4px}.good{color:var(--good)}.big{font-size:28px;font-weight:700;margin:0}"
		+ ".goals{display:grid;grid-template-columns:repeat(auto-fit,minmax(280px,1fr));gap:12px}"
		+ ".goal{border:1px solid var(--line);border-radius:10px;padding:12px}.goal p{margin:6px 0 0;font-size:13px}"
		+ ".gh{display:flex;justify-content:space-between;gap:8px;margin-bottom:8px}"
		+ ".status{font-size:12px;font-weight:700;text-transform:uppercase}.s-on_track,.s-complete{color:var(--good)}"
		+ ".s-slightly_behind{color:var(--warn)}.s-behind,.s-overdue{color:var(--bad)}.s-tracking,.s-no_target{color:var(--gold)}"
		+ ".timeline{display:grid;gap:10px}.event{border-left:3px solid;padding:6px 12px}.et{font-size:12px;font-weight:700;text-transform:uppercase}"
		+ ".title{font-weight:700}.event img{display:block;max-width:100%;margin-top:8px;border-radius:8px}"
		+ ".tile .d{font-size:12px;margin-top:2px}.bad{color:var(--bad)}.small{font-size:12px}"
		+ ".charts{display:grid;grid-template-columns:repeat(auto-fit,minmax(400px,1fr));gap:16px}"
		+ "svg.chart{width:100%;height:220px;display:block}svg.bars{width:100%;height:auto;display:block}"
		+ "svg rect:hover,svg circle:hover{opacity:.75}"
		+ ".stats{display:grid;grid-template-columns:repeat(auto-fit,minmax(170px,1fr));gap:12px;margin-bottom:12px}"
		+ ".stats .l{color:var(--muted);font-size:12px;text-transform:uppercase}.stats .v{font-size:20px;font-weight:700}"
		+ "details{margin-top:12px}summary{cursor:pointer;color:var(--gold)}"
		+ "footer{color:var(--muted);font-size:12px;text-align:center;margin-top:24px}"
		+ "@media(max-width:600px){h1{font-size:24px}.barcol{display:none}}";
}

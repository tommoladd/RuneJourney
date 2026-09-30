package com.runejourney.wrapped;

import com.runejourney.util.Format;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import lombok.Getter;
import net.runelite.client.ui.FontManager;

/**
 * Draws a Wrapped slide with time-based animation. Used by both the in-game overlay and the window,
 * so it only depends on a Graphics2D, a size and the elapsed time.
 */
public final class WrappedRenderer
{
	/**
	 * Base time a slide stays up before advancing on its own, plus time per extra line.
	 */
	public static final long SLIDE_MS = 6500;
	public static final long LINE_MS = 700;

	/**
	 * [top, bottom, accent] per theme.
	 */
	private static final Color[][] PALETTES = {
		{new Color(0x1B1035), new Color(0x4A1F6E), new Color(0xF2C14E)},
		{new Color(0x0B2A2F), new Color(0x13705F), new Color(0xB8F2E6)},
		{new Color(0x2B1407), new Color(0x93511A), new Color(0xFFE08A)},
		{new Color(0x0D1B3E), new Color(0x2356A8), new Color(0x9AD1FF)},
		{new Color(0x2A0B0B), new Color(0x8E2525), new Color(0xFFB199)},
	};

	private static final Color DIM = new Color(0, 0, 0, 170);

	/**
	 * Button areas from the last paint, for click handling.
	 */
	@Getter
	public static class Layout
	{
		private Rectangle back;
		private Rectangle next;
		private Rectangle close;
		private Rectangle card;
	}

	private WrappedRenderer()
	{
	}

	public static long durationOf(WrappedWeek.Slide s)
	{
		return SLIDE_MS + Math.max(0, s.getLines().size() - 1) * LINE_MS + (s.getSummary().isEmpty() ? 0 : 2500);
	}

	public static Layout paint(Graphics2D g, int w, int h, WrappedWeek week, int index, long slideMs, long totalMs,
		BufferedImage screenshot, Point hover, boolean dimBackground, Function<String, BufferedImage> icons)
	{
		Layout layout = new Layout();
		Color[] palette = PALETTES[Math.floorMod(week.getTheme(), PALETTES.length)];
		Color accent = palette[2];
		WrappedWeek.Slide slide = week.getSlides().get(index);

		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		Composite base = g.getComposite();

		if (dimBackground)
		{
			g.setColor(DIM);
			g.fillRect(0, 0, w, h);
		}

		// Card
		int cw = dimBackground ? Math.min(w - 24, Math.max(460, (int) (w * 0.66))) : w;
		int ch = dimBackground ? Math.min(h - 24, Math.max(380, (int) (h * 0.84))) : h;
		int cx = (w - cw) / 2;
		int cy = (h - ch) / 2;
		layout.card = new Rectangle(cx, cy, cw, ch);
		Shape card = new RoundRectangle2D.Double(cx, cy, cw, ch, dimBackground ? 26 : 0, dimBackground ? 26 : 0);

		// The card pops in on open
		double open = ease(clamp(totalMs / 350.0));
		AffineTransform at = g.getTransform();
		if (open < 1)
		{
			double scale = 0.92 + 0.08 * open;
			g.translate(cx + cw / 2.0, cy + ch / 2.0);
			g.scale(scale, scale);
			g.translate(-(cx + cw / 2.0), -(cy + ch / 2.0));
			g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, (float) open));
		}

		// Background: gradient that slowly breathes between slides
		double breathe = 0.5 + 0.5 * Math.sin(totalMs / 2400.0);
		Color top = mix(palette[0], palette[1], 0.15 * breathe);
		g.setPaint(new GradientPaint(cx, cy, top, cx + cw * 0.3f, cy + ch, palette[1]));
		g.fill(card);
		java.awt.Shape oldClip = g.getClip();
		g.clip(card);

		// Soft light blobs and drifting sparkles
		drawBlob(g, cx + cw * (0.2 + 0.1 * Math.sin(totalMs / 3100.0)), cy + ch * 0.25, cw * 0.5, accent, 0.10f);
		drawBlob(g, cx + cw * (0.85 + 0.05 * Math.cos(totalMs / 2700.0)), cy + ch * 0.8, cw * 0.45, Color.WHITE, 0.06f);
		for (int i = 0; i < 26; i++)
		{
			long s = (i * 7919L + week.getTheme() * 104729L) & 0xFFFF;
			double speed = 0.012 + (s % 13) * 0.002;
			double px = cx + ((s * 37 % 1000) / 1000.0) * cw + Math.sin((totalMs / 1000.0) + i) * 8;
			double py = cy + ch - ((totalMs * speed + s * 3) % (ch + 40)) + 20;
			float a = 0.15f + (s % 5) * 0.06f;
			int r = 2 + (int) (s % 3);
			g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, a * (float) open));
			g.setColor(i % 3 == 0 ? accent : Color.WHITE);
			g.fillOval((int) px, (int) py, r, r);
		}
		g.setComposite(open < 1 ? AlphaComposite.getInstance(AlphaComposite.SRC_OVER, (float) open) : base);

		// Progress segments
		int n = week.getSlides().size();
		int pad = Math.max(16, cw / 30);
		int gap = 4;
		int segW = (cw - pad * 2 - gap * (n - 1)) / Math.max(1, n);
		double slideProgress = clamp(slideMs / (double) durationOf(slide));
		for (int i = 0; i < n; i++)
		{
			int sx = cx + pad + i * (segW + gap);
			int sy = cy + pad;
			g.setColor(new Color(255, 255, 255, 60));
			g.fillRoundRect(sx, sy, segW, 4, 4, 4);
			double fill = i < index ? 1 : i == index ? slideProgress : 0;
			g.setColor(Color.WHITE);
			g.fillRoundRect(sx, sy, (int) (segW * fill), 4, 4, 4);
		}

		// Brand
		Font small = FontManager.getRunescapeSmallFont();
		g.setFont(small.deriveFont((float) Math.max(16, ch / 28)));
		g.setColor(new Color(255, 255, 255, 170));
		g.drawString("RuneJourney Wrapped", cx + pad, cy + pad + 26);
		String range = Format.date(week.getWeekStart()) + " - " + Format.date(week.getWeekEnd());
		FontMetrics fmSmall = g.getFontMetrics();
		g.drawString(range, cx + cw - pad - fmSmall.stringWidth(range), cy + pad + 26);

		// Content
		BufferedImage icon = slide.getIcon() == null ? null : icons.apply(slide.getIcon());
		// Slides with lists, summaries or screenshots need the room, so everything above them shrinks
		boolean dense = !slide.getLines().isEmpty() || !slide.getSummary().isEmpty() || slide.getScreenshot() != null;
		int contentTop = cy + (int) (ch * (icon != null ? (dense ? 0.1 : 0.11) : (dense ? 0.14 : 0.2)));
		int contentWidth = cw - pad * 4;
		int centerX = cx + cw / 2;
		int y = contentTop;

		Font regular = FontManager.getRunescapeFont();
		Font bold = FontManager.getRunescapeBoldFont();

		// Buttons sit along the bottom; content must stay above them
		g.setFont(regular.deriveFont((float) Math.max(18, ch / 24)));
		FontMetrics fb = g.getFontMetrics();
		int by = cy + ch - pad - fb.getHeight() - 8;
		g.setFont(small.deriveFont((float) Math.max(14, ch / 32)));
		int hintY = by - 10;
		int contentBottom = hintY - g.getFontMetrics().getHeight() - 6;

		if (icon != null)
		{
			int size = (int) (dense ? Math.min(64, ch * 0.11) : Math.min(104, ch * 0.16));
			// Pops in with a little overshoot, then floats gently
			double t = clamp(slideMs / 550.0);
			double pop = t < 1 ? backEase(t) : 1;
			double bob = Math.sin(totalMs / 520.0) * size * 0.04;
			int iconY = y + size / 2;
			g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, (float) (0.16 * clamp(t * 2))));
			g.setColor(accent);
			int glow = (int) (size * 1.5 * pop);
			g.fillOval(centerX - glow / 2, iconY - glow / 2, glow, glow);
			g.setComposite(open < 1 ? AlphaComposite.getInstance(AlphaComposite.SRC_OVER, (float) open) : base);
			drawIcon(g, icon, centerX, (int) (iconY + bob), (int) (size * pop));
			y += size + (int) (ch * 0.02);
		}

		if (slide.getEyebrow() != null)
		{
			double t = ease(clamp(slideMs / 450.0));
			g.setFont(regular.deriveFont((float) Math.max(20, ch / 18)));
			y += g.getFontMetrics().getAscent();
			drawCentered(g, slide.getEyebrow(), centerX, y + (int) ((1 - t) * 14), accent, t);
			y += (int) (ch * 0.03);
		}

		if (!Double.isNaN(slide.getValue()))
		{
			double t = ease(clamp((slideMs - 300) / 1500.0));
			double pop = slideMs < 300 ? 0 : slideMs < 1900 ? 0.9 + 0.1 * t : 1 + 0.06 * Math.max(0, 1 - (slideMs - 1900) / 300.0);
			String text = formatValue(slide.getValue() * t, slide.getFormat());
			float size = (float) Math.max(36, dense ? ch / 7.0 : ch / 5.2);
			g.setFont(bold.deriveFont(size));
			FontMetrics fm = g.getFontMetrics();
			while (fm.stringWidth(formatValue(slide.getValue(), slide.getFormat())) > contentWidth && size > 24)
			{
				size -= 4;
				g.setFont(bold.deriveFont(size));
				fm = g.getFontMetrics();
			}
			y += fm.getAscent();
			AffineTransform before = g.getTransform();
			g.translate(centerX, y - fm.getAscent() / 2.0);
			g.scale(Math.max(0.01, pop), Math.max(0.01, pop));
			g.translate(-centerX, -(y - fm.getAscent() / 2.0));
			drawCentered(g, text, centerX, y, Color.WHITE, clamp((slideMs - 300) / 300.0));
			g.setTransform(before);
			y += fm.getDescent();
		}
		else if (slide.getHeadline() != null)
		{
			double t = ease(clamp((slideMs - 250) / 700.0));
			float size = (float) Math.max(30, ch / 9);
			g.setFont(bold.deriveFont(size));
			List<String> rows = wrap(g, slide.getHeadline(), contentWidth);
			while (rows.size() > 3 && size > 22)
			{
				size -= 4;
				g.setFont(bold.deriveFont(size));
				rows = wrap(g, slide.getHeadline(), contentWidth);
			}
			FontMetrics fm = g.getFontMetrics();
			for (String row : rows)
			{
				y += fm.getAscent();
				drawCentered(g, row, centerX, y + (int) ((1 - t) * 20), Color.WHITE, t);
				y += fm.getDescent() + 4;
			}
		}

		if (slide.getUnit() != null)
		{
			double t = ease(clamp((slideMs - 900) / 500.0));
			g.setFont(regular.deriveFont((float) Math.max(20, dense ? ch / 18 : ch / 14)));
			y += g.getFontMetrics().getAscent();
			drawCentered(g, slide.getUnit(), centerX, y, new Color(255, 255, 255, 230), t);
		}

		if (slide.getSubtitle() != null)
		{
			double t = ease(clamp((slideMs - 1300) / 600.0));
			g.setFont(regular.deriveFont((float) Math.max(18, ch / 22)));
			FontMetrics fm = g.getFontMetrics();
			y += (int) (ch * (dense ? 0.02 : 0.04));
			for (String row : wrap(g, slide.getSubtitle(), contentWidth))
			{
				y += fm.getAscent();
				drawCentered(g, row, centerX, y + (int) ((1 - t) * 10), accent, t);
				y += fm.getDescent();
			}
		}

		// Supporting lines, one after another
		if (!slide.getLines().isEmpty())
		{
			g.setFont(regular.deriveFont((float) Math.max(17, ch / 24)));
			FontMetrics fm = g.getFontMetrics();
			int iconSize = Math.max(24, fm.getHeight() + 6);
			int rowH = Math.max(fm.getHeight(), iconSize) + 6;
			y += (int) (ch * 0.03);
			for (int i = 0; i < slide.getLines().size(); i++)
			{
				if (y + rowH > contentBottom)
				{
					break;
				}
				double t = ease(clamp((slideMs - 1900 - i * 260) / 450.0));
				String line = slide.getLines().get(i);
				String key = i < slide.getLineIcons().size() ? slide.getLineIcons().get(i) : null;
				BufferedImage lineIcon = key == null ? null : icons.apply(key);
				int iconW = lineIcon != null ? iconSize + 6 : 0;
				int lw = fm.stringWidth(line) + 24 + iconW;
				int lx = centerX - lw / 2 - (int) ((1 - t) * 30);
				float alpha = (float) (t * (open < 1 ? open : 1));
				g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.22f * alpha));
				g.setColor(Color.BLACK);
				g.fillRoundRect(lx, y, lw, rowH - 4, rowH - 4, rowH - 4);
				g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));
				if (lineIcon != null)
				{
					drawIcon(g, lineIcon, lx + 12 + iconSize / 2, y + (rowH - 4) / 2, iconSize);
				}
				g.setColor(Color.WHITE);
				g.drawString(line, lx + 12 + iconW, y + (rowH - 4 + fm.getAscent() - fm.getDescent()) / 2);
				g.setComposite(base);
				y += rowH;
			}
		}

		// Screenshot for the best moment
		if (screenshot != null)
		{
			double t = ease(clamp((slideMs - 1000) / 700.0));
			int maxW = (int) (cw * 0.6);
			int maxH = cy + ch - pad * 4 - y - 20;
			if (maxH > 60)
			{
				double scale = Math.min(maxW / (double) screenshot.getWidth(), maxH / (double) screenshot.getHeight());
				int iw = (int) (screenshot.getWidth() * scale * (0.9 + 0.1 * t));
				int ih = (int) (screenshot.getHeight() * scale * (0.9 + 0.1 * t));
				int ix = centerX - iw / 2;
				int iy = y + 20;
				g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, (float) t));
				Shape oldClip2 = g.getClip();
				g.clip(new RoundRectangle2D.Double(ix, iy, iw, ih, 16, 16));
				g.drawImage(screenshot, ix, iy, iw, ih, null);
				g.setClip(oldClip2);
				g.setColor(accent);
				g.setStroke(new BasicStroke(2f));
				g.draw(new RoundRectangle2D.Double(ix, iy, iw, ih, 16, 16));
				g.setComposite(base);
			}
		}

		// Summary grid on the last slide
		if (!slide.getSummary().isEmpty())
		{
			int cols = 3;
			int rowsCount = (slide.getSummary().size() + cols - 1) / cols;
			int tileW = Math.min(200, (contentWidth - 20) / cols);
			int space = contentBottom - (y + 24) - 10 * (rowsCount - 1);
			int tileH = Math.max(40, Math.min(ch / 7, space / Math.max(1, rowsCount)));
			// Show only as many rows as fit above the buttons
			int rowsFit = Math.max(1, (contentBottom - (y + 24) + 10) / (tileH + 10));
			int shown = Math.min(slide.getSummary().size(), rowsFit * cols);
			int gridW = cols * tileW + (cols - 1) * 10;
			int gx = centerX - gridW / 2;
			int gy = y + 24;
			for (int i = 0; i < shown; i++)
			{
				double t = ease(clamp((slideMs - 700 - i * 180) / 450.0));
				int tx = gx + (i % cols) * (tileW + 10);
				int ty = gy + (i / cols) * (tileH + 10) + (int) ((1 - t) * 16);
				g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, (float) (0.28 * t)));
				g.setColor(Color.BLACK);
				g.fillRoundRect(tx, ty, tileW, tileH, 14, 14);
				g.setComposite(base);
				String[] stat = slide.getSummary().get(i);
				float valueSize = (float) Math.min(tileH * 0.42, Math.max(18, tileH / 2.6));
				float labelSize = (float) Math.min(tileH * 0.26, Math.max(13, tileH / 5.0));
				g.setFont(bold.deriveFont(valueSize));
				FontMetrics fv = g.getFontMetrics();
				drawCentered(g, stat[1], tx + tileW / 2, ty + (int) (tileH * 0.12) + fv.getAscent(), Color.WHITE, t);
				g.setFont(small.deriveFont(labelSize));
				drawCentered(g, stat[0], tx + tileW / 2, ty + tileH - (int) (tileH * 0.12), accent, t);
			}
			if (rowsCount > 0)
			{
				y = gy + rowsCount * (tileH + 10);
			}
		}

		// Buttons
		g.setComposite(open < 1 ? AlphaComposite.getInstance(AlphaComposite.SRC_OVER, (float) open) : base);
		g.setFont(regular.deriveFont((float) Math.max(18, ch / 24)));
		boolean last = index == n - 1;
		layout.next = button(g, last ? "Close" : "Next >", cx + cw - pad, by, true, accent, hover, fb);
		layout.close = last ? layout.next : button(g, "Close", layout.next.x - 10, by, true, accent, hover, fb);
		layout.back = index > 0 ? button(g, "< Back", cx + pad, by, false, accent, hover, fb) : null;
		g.setFont(small.deriveFont((float) Math.max(14, ch / 32)));
		drawCentered(g, "Click to continue · Esc to close", centerX, hintY, new Color(255, 255, 255, 120), 1);

		g.setClip(oldClip);
		g.setTransform(at);
		g.setComposite(base);
		return layout;
	}

	private static Rectangle button(Graphics2D g, String text, int x, int y, boolean alignRight, Color accent, Point hover, FontMetrics fm)
	{
		int bw = fm.stringWidth(text) + 28;
		int bh = fm.getHeight() + 8;
		int bx = alignRight ? x - bw : x;
		Rectangle r = new Rectangle(bx, y, bw, bh);
		boolean over = hover != null && r.contains(hover);
		g.setColor(over ? accent : new Color(255, 255, 255, 40));
		g.fillRoundRect(bx, y, bw, bh, bh, bh);
		g.setColor(over ? Color.BLACK : Color.WHITE);
		g.drawString(text, bx + 14, y + 4 + fm.getAscent());
		return r;
	}

	/**
	 * Draws game art centred on a point, scaled with nearest-neighbour so pixels stay crisp.
	 */
	private static void drawIcon(Graphics2D g, BufferedImage img, int cx, int cy, int size)
	{
		if (img == null || size <= 0 || img.getWidth() <= 0 || img.getHeight() <= 0)
		{
			return;
		}
		double scale = size / (double) Math.max(img.getWidth(), img.getHeight());
		int w = (int) Math.round(img.getWidth() * scale);
		int h = (int) Math.round(img.getHeight() * scale);
		Object old = g.getRenderingHint(RenderingHints.KEY_INTERPOLATION);
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
		g.drawImage(img, cx - w / 2, cy - h / 2, w, h, null);
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, old == null ? RenderingHints.VALUE_INTERPOLATION_BILINEAR : old);
	}

	/**
	 * Ease with a small overshoot, for things that pop in.
	 */
	private static double backEase(double t)
	{
		double c1 = 1.70158;
		double c3 = c1 + 1;
		return 1 + c3 * Math.pow(t - 1, 3) + c1 * Math.pow(t - 1, 2);
	}

	private static void drawBlob(Graphics2D g, double x, double y, double size, Color c, float alpha)
	{
		Composite old = g.getComposite();
		for (int i = 0; i < 6; i++)
		{
			double s = size * (1 - i * 0.14);
			g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha / 3));
			g.setColor(c);
			g.fillOval((int) (x - s / 2), (int) (y - s / 2), (int) s, (int) s);
		}
		g.setComposite(old);
	}

	private static void drawCentered(Graphics2D g, String text, int cx, int y, Color color, double alpha)
	{
		if (alpha <= 0)
		{
			return;
		}
		Composite old = g.getComposite();
		float a = (float) Math.min(1, alpha);
		if (old instanceof AlphaComposite)
		{
			a *= ((AlphaComposite) old).getAlpha();
		}
		g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, a));
		FontMetrics fm = g.getFontMetrics();
		int x = cx - fm.stringWidth(text) / 2;
		g.setColor(color);
		g.drawString(text, x, y);
		g.setComposite(old);
	}

	private static List<String> wrap(Graphics2D g, String text, int width)
	{
		List<String> rows = new ArrayList<>();
		FontMetrics fm = g.getFontMetrics();
		StringBuilder row = new StringBuilder();
		for (String word : text.split(" "))
		{
			String next = row.length() == 0 ? word : row + " " + word;
			if (fm.stringWidth(next) > width && row.length() > 0)
			{
				rows.add(row.toString());
				row = new StringBuilder(word);
			}
			else
			{
				row = new StringBuilder(next);
			}
		}
		if (row.length() > 0)
		{
			rows.add(row.toString());
		}
		return rows;
	}

	static String formatValue(double v, WrappedWeek.ValueFormat format)
	{
		long n = Math.round(v);
		switch (format)
		{
			case GP:
				return Format.compact(n);
			case DURATION:
				return Format.duration(n);
			default:
				return Format.number(n);
		}
	}

	private static double clamp(double v)
	{
		return Math.max(0, Math.min(1, v));
	}

	private static double ease(double t)
	{
		return 1 - Math.pow(1 - t, 3);
	}

	private static Color mix(Color a, Color b, double t)
	{
		return new Color(
			(int) (a.getRed() + (b.getRed() - a.getRed()) * t),
			(int) (a.getGreen() + (b.getGreen() - a.getGreen()) * t),
			(int) (a.getBlue() + (b.getBlue() - a.getBlue()) * t));
	}
}

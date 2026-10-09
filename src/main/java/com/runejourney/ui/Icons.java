package com.runejourney.ui;

import java.awt.*;
import java.awt.image.BufferedImage;

public final class Icons
{
	private Icons()
	{
	}

	public static BufferedImage navIcon()
	{
		BufferedImage img = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = img.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setColor(new Color(0xE0B040));
		g.setStroke(new BasicStroke(1.5f));
		g.drawOval(1, 1, 13, 13);
		g.fillPolygon(new int[]{8, 10, 8, 6}, new int[]{3, 8, 13, 8}, 4);
		g.setColor(new Color(0xFFF3C4));
		g.fillPolygon(new int[]{8, 10, 8}, new int[]{3, 8, 8}, 3);
		g.dispose();
		return img;
	}
}

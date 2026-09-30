package com.runejourney.wrapped;

import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.client.input.KeyListener;
import net.runelite.client.input.MouseListener;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * Draws Wrapped over the game. While it's showing it takes mouse clicks and a few keys (so clicking
 * through the slides doesn't walk your character around); it never sends input to the game.
 */
@Singleton
class WrappedOverlay extends Overlay implements MouseListener, KeyListener
{
	private final Client client;
	private WrappedPlayer player;

	@Inject
	WrappedOverlay(Client client)
	{
		this.client = client;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ALWAYS_ON_TOP);
		setPriority(Overlay.PRIORITY_HIGHEST);
	}

	void setPlayer(WrappedPlayer player)
	{
		this.player = player;
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		WrappedPlayer.Frame f = player == null ? null : player.frame();
		if (f == null)
		{
			return null;
		}
		int w = client.getCanvasWidth();
		int h = client.getCanvasHeight();
		player.setLayout(WrappedRenderer.paint(graphics, w, h, f.week, f.index, f.slideMs, f.totalMs, f.screenshot, f.hover, true, player::icon));
		return new Dimension(w, h);
	}

	private boolean active()
	{
		return player != null && player.isInGame();
	}

	private Point canvasPoint()
	{
		net.runelite.api.Point p = client.getMouseCanvasPosition();
		return new Point(p.getX(), p.getY());
	}

	@Override
	public MouseEvent mousePressed(MouseEvent e)
	{
		if (active())
		{
			player.click(canvasPoint());
			e.consume();
		}
		return e;
	}

	@Override
	public MouseEvent mouseReleased(MouseEvent e)
	{
		if (active())
		{
			e.consume();
		}
		return e;
	}

	@Override
	public MouseEvent mouseClicked(MouseEvent e)
	{
		if (active())
		{
			e.consume();
		}
		return e;
	}

	@Override
	public MouseEvent mouseMoved(MouseEvent e)
	{
		if (active())
		{
			player.setHover(canvasPoint());
		}
		return e;
	}

	@Override
	public MouseEvent mouseDragged(MouseEvent e)
	{
		if (active())
		{
			e.consume();
		}
		return e;
	}

	@Override
	public MouseEvent mouseEntered(MouseEvent e)
	{
		return e;
	}

	@Override
	public MouseEvent mouseExited(MouseEvent e)
	{
		return e;
	}

	@Override
	public void keyPressed(KeyEvent e)
	{
		if (!active())
		{
			return;
		}
		switch (e.getKeyCode())
		{
			case KeyEvent.VK_ESCAPE:
				player.close();
				e.consume();
				break;
			case KeyEvent.VK_RIGHT:
			case KeyEvent.VK_SPACE:
			case KeyEvent.VK_ENTER:
				player.next();
				e.consume();
				break;
			case KeyEvent.VK_LEFT:
				player.back();
				e.consume();
				break;
			default:
				break;
		}
	}

	@Override
	public void keyTyped(KeyEvent e)
	{
	}

	@Override
	public void keyReleased(KeyEvent e)
	{
	}
}

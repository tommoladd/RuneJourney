package com.runejourney.wrapped;

import com.runejourney.ui.Icons;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.Timer;
import javax.swing.WindowConstants;

/**
 * Plays Wrapped in its own window, used when you're not logged in or have turned off the in-game view.
 */
class WrappedWindow extends JFrame
{
	private final Timer timer;

	WrappedWindow(WrappedPlayer player)
	{
		setTitle("RuneJourney Wrapped");
		setIconImage(Icons.navIcon());
		setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);

		JComponent canvas = new JComponent()
		{
			@Override
			protected void paintComponent(Graphics g)
			{
				WrappedPlayer.Frame f = player.frame();
				if (f == null)
				{
					return;
				}
				player.setLayout(WrappedRenderer.paint((Graphics2D) g, getWidth(), getHeight(), f.week, f.index, f.slideMs,
					f.totalMs, f.screenshot, f.hover, false, player::icon));
			}
		};
		canvas.setPreferredSize(new Dimension(900, 600));
		canvas.setFocusable(true);
		MouseAdapter mouse = new MouseAdapter()
		{
			@Override
			public void mousePressed(MouseEvent e)
			{
				player.click(e.getPoint());
			}

			@Override
			public void mouseMoved(MouseEvent e)
			{
				player.setHover(e.getPoint());
			}
		};
		canvas.addMouseListener(mouse);
		canvas.addMouseMotionListener(mouse);
		canvas.addKeyListener(new KeyAdapter()
		{
			@Override
			public void keyPressed(KeyEvent e)
			{
				switch (e.getKeyCode())
				{
					case KeyEvent.VK_ESCAPE:
						player.close();
						break;
					case KeyEvent.VK_RIGHT:
					case KeyEvent.VK_SPACE:
					case KeyEvent.VK_ENTER:
						player.next();
						break;
					case KeyEvent.VK_LEFT:
						player.back();
						break;
					default:
						break;
				}
			}
		});
		setContentPane(canvas);
		pack();
		setLocationRelativeTo(null);

		// ~60fps while open
		timer = new Timer(16, e -> canvas.repaint());
		timer.start();
		addWindowListener(new WindowAdapter()
		{
			@Override
			public void windowClosed(WindowEvent e)
			{
				timer.stop();
				player.close();
			}

			@Override
			public void windowOpened(WindowEvent e)
			{
				canvas.requestFocusInWindow();
			}
		});
	}
}

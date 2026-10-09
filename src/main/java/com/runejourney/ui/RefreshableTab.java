package com.runejourney.ui;

import javax.swing.JPanel;

abstract class RefreshableTab extends JPanel
{
	abstract void refresh(boolean force);

	void rebuild()
	{
		revalidate();
		repaint();
	}
}

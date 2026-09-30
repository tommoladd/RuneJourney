package com.runejourney.ui;

import java.awt.Component;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JList;
import net.runelite.api.Skill;

/**
 * Shows skills by their in-game name ("Attack") rather than the enum constant ("ATTACK").
 */
class SkillRenderer extends DefaultListCellRenderer
{
	@Override
	public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus)
	{
		Object shown = value instanceof Skill ? ((Skill) value).getName() : value;
		return super.getListCellRendererComponent(list, shown, index, isSelected, cellHasFocus);
	}
}

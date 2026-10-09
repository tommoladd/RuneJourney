package com.runejourney.ui;

import java.awt.Component;
import javax.swing.*;
import net.runelite.api.Skill;

class SkillRenderer extends DefaultListCellRenderer
{
	@Override
	public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus)
	{
		Object shown = value instanceof Skill ? ((Skill) value).getName() : value;
		return super.getListCellRendererComponent(list, shown, index, isSelected, cellHasFocus);
	}
}

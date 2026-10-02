package com.runejourney.model;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class JourneyEvent
{
	/**
	 * Epoch millis when the event happened.
	 */
	private long time;
	private EventType type;
	private String title;
	private String detail;
	/**
	 * Skill name for skill-related events, used to show the skill icon.
	 */
	private String skill;
	/**
	 * File name of an associated screenshot inside the profile's screenshot folder.
	 */
	private String screenshot;
	/**
	 * Highlights are shown on the Today page and in recaps.
	 */
	private boolean highlight;
	/**
	 * GP value associated with the event (drops), used to rank "best moments".
	 */
	private long value;
	/**
	 * The player's own note about this moment, added from the Journey.
	 */
	private String note;

	public JourneyEvent(long time, EventType type, String title, String detail, String skill, String screenshot,
		boolean highlight, long value)
	{
		this.time = time;
		this.type = type;
		this.title = title;
		this.detail = detail;
		this.skill = skill;
		this.screenshot = screenshot;
		this.highlight = highlight;
		this.value = value;
	}
}

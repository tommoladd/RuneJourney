package com.runejourney.model;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class JourneyEvent
{
	/**
	 * Identifies the event across saves and devices, see {@link com.runejourney.sync.EventIds}.
	 */
	private String id;
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
	/**
	 * Happened while RuneJourney wasn't running, some time since the day's
	 * {@link DayRecord#getAwayFrom()}; it was only noticed when this was recorded.
	 */
	private boolean away;
	/**
	 * Added by the player as a memory, not recorded from the game. Like notes, memories only show on
	 * a public page when the player chooses.
	 */
	private boolean memory;

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

	public JourneyEvent copy()
	{
		JourneyEvent c = new JourneyEvent(time, type, title, detail, skill, screenshot, highlight, value);
		c.setId(id);
		c.setNote(note);
		c.setAway(away);
		c.setMemory(memory);
		return c;
	}
}

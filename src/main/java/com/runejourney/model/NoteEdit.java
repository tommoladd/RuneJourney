package com.runejourney.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A note one PC wrote on a Journey event, and when (by logical clock). The newest note wins; a
 * null note removes it.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class NoteEdit
{
	private String text;
	private long clock;
}

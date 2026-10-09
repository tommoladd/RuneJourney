package com.runejourney.model;

import lombok.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class NoteEdit
{
	private String text;
	private long clock;
}

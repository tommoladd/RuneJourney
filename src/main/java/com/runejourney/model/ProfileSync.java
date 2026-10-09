package com.runejourney.model;

import java.util.*;
import lombok.Data;

@Data
public class ProfileSync
{
	private ProfileSlice own;
	private Map<String, ProfileSlice> remotes = new HashMap<>();
}

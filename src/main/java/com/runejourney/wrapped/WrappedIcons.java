package com.runejourney.wrapped;

import java.util.*;
import net.runelite.api.gameval.ItemID;

public final class WrappedIcons
{
	public static final String INTRO = item(ItemID.FIREWORK);
	public static final String TIME = item(ItemID.RD_HOURGLASS);
	public static final String COINS = "item:" + ItemID.COINS + ":10000";
	public static final String CLUE = item(ItemID.TRAIL_REWARD_CASKET_ELITE);
	public static final String DEATH = item(ItemID.SKULL);
	public static final String COLLECTION_LOG = item(ItemID.COLLECTION_LOG);
	public static final String GOAL = item(ItemID.FIREWORK);
	public static final String PLAN = item(ItemID.HUNDRED_REWARDLAMP);
	public static final String RECORD = item(ItemID.SKILLCAPE_MAX);
	public static final String STREAK = item(ItemID.TINDERBOX);
	public static final String BOSS = item(ItemID.SKULL);

	private static final Map<String, Integer> BOSS_PETS = new HashMap<>();

	static
	{
		pet("Abyssal Sire", ItemID.ABYSSALSIRE_PET);
		pet("Alchemical Hydra", ItemID.HYDRAPET);
		pet("Amoxliatl", ItemID.AMOXLIATLPET);
		pet("Araxxor", ItemID.ARAXXORPET);
		pet("Barrows", ItemID.BARROWS_DHAROK_HEAD);
		pet("Brutus", ItemID.COWBOSSPET);
		pet("Callisto", ItemID.CALLISTO_PET);
		pet("Cerberus", ItemID.HELL_PET);
		pet("Chambers of Xeric", ItemID.OLMPET);
		pet("Chaos Elemental", ItemID.CHAOSELEPET);
		pet("Commander Zilyana", ItemID.SARADOMINPET);
		pet("Corporeal Beast", ItemID.COREPET);
		pet("Corrupted Gauntlet", ItemID.GAUNTLETPET);
		pet("Dagannoth Prime", ItemID.PRIMEPET);
		pet("Dagannoth Rex", ItemID.REXPET);
		pet("Dagannoth Supreme", ItemID.SUPREMEPET);
		pet("Doom of Mokhaiotl", ItemID.DOMPET);
		pet("Duke Sucellus", ItemID.DUKESUCELLUSPET);
		pet("Gauntlet", ItemID.GAUNTLETPET);
		pet("General Graardor", ItemID.BANDOSPET);
		pet("Giant Mole", ItemID.MOLEPET);
		pet("Grotesque Guardians", ItemID.DAWNPET);
		pet("Guardians of the Rift", ItemID.ABYSSALPET);
		pet("K'ril Tsutsaroth", ItemID.ZAMORAKPET);
		pet("Kalphite Queen", ItemID.SKULL);
		pet("King Black Dragon", ItemID.KBDPET);
		pet("Kraken", ItemID.KRAKENPET);
		pet("Kree'arra", ItemID.ARMADYLPET);
		pet("Nex", ItemID.NEXPET);
		pet("Nightmare", ItemID.NIGHTMAREPET);
		pet("Phosani's Nightmare", ItemID.NIGHTMAREPET);
		pet("Phantom Muspah", ItemID.MUSPAHPET);
		pet("Sarachnis", ItemID.SARACHNISPET);
		pet("Scorpia", ItemID.SCORPIA_PET);
		pet("Scurrius", ItemID.SCURRIUSPET);
		pet("Skotizo", ItemID.SKOTIZOPET);
		pet("Sol Heredit", ItemID.SOLHEREDITPET);
		pet("Tempoross", ItemID.TEMPOROSSPET);
		pet("The Hueycoatl", ItemID.HUEYPET);
		pet("The Leviathan", ItemID.LEVIATHANPET);
		pet("The Royal Titans", ItemID.RTBRANDAPET);
		pet("The Whisperer", ItemID.WHISPERERPET);
		pet("Theatre of Blood", ItemID.VERZIKPET);
		pet("Thermonuclear Smoke Devil", ItemID.SMOKEPET);
		pet("Tombs of Amascut", ItemID.WARDENPET_TUMEKEN);
		pet("TzKal-Zuk", ItemID.INFERNOPET);
		pet("TzTok-Jad", ItemID.JAD_PET);
		pet("Vardorvis", ItemID.VARDORVISPET);
		pet("Venenatis", ItemID.VENENATIS_PET);
		pet("Vet'ion", ItemID.VETION_PET);
		pet("Vorkath", ItemID.VORKATHPET);
		pet("Wintertodt", ItemID.PHOENIXPET);
		pet("Yama", ItemID.YAMAPET);
		pet("Zalcano", ItemID.ZALCANOPET);
		pet("Zulrah", ItemID.SNAKEPET);
	}

	private WrappedIcons()
	{
	}

	private static void pet(String boss, int itemId)
	{
		BOSS_PETS.put(boss.toLowerCase(Locale.ENGLISH), itemId);
	}

	public static String item(int id)
	{
		return "item:" + id;
	}

	public static String skill(String skill)
	{
		return "skill:" + skill;
	}

	public static String byName(String name)
	{
		return "name:" + name.replaceFirst("^[\\d,]+ x ", "").replaceFirst("^Pet: ", "");
	}

	public static String boss(String boss)
	{
		String lower = boss.toLowerCase(Locale.ENGLISH);
		Integer best = BOSS_PETS.get(lower);
		if (best == null)
		{
			int bestLength = 0;
			for (Map.Entry<String, Integer> e : BOSS_PETS.entrySet())
			{
				if (lower.startsWith(e.getKey()) && e.getKey().length() > bestLength)
				{
					best = e.getValue();
					bestLength = e.getKey().length();
				}
			}
		}
		return best == null ? BOSS : item(best);
	}
}

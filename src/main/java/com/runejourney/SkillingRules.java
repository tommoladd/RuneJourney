package com.runejourney;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import lombok.Value;
import net.runelite.api.Skill;

/**
 * Which skilling actions make money, and how much. Gathering skills earn the value of what they
 * produce (logs, ore, fish...); processing skills earn the value they add (product minus inputs).
 * Skills that only use items up, like Prayer or Firemaking, never count as income.
 */
final class SkillingRules
{
	enum Mode
	{
		/**
		 * Value of the products gained; anything used up (bait, seeds) is ignored.
		 */
		GATHER,
		/**
		 * Everything gained minus everything used, but only when something was made.
		 */
		PROCESS
	}

	@Value
	static class Change
	{
		String name;
		/**
		 * Positive when gained, negative when used up.
		 */
		int quantity;
		long unitPrice;
	}

	@Value
	static class Income
	{
		Skill skill;
		long value;
		/**
		 * The gained items that counted, for item goals.
		 */
		List<Change> products;
	}

	@Value
	private static class Rule
	{
		Mode mode;
		Predicate<String> product;
	}

	private static final Map<Skill, Rule> RULES = new EnumMap<>(Skill.class);
	/**
	 * Made with Crafting but only worth anything as part of a birdhouse run, which is Hunter.
	 */
	private static final Pattern BIRDHOUSE = Pattern.compile("bird ?house$");

	static
	{
		gather(Skill.WOODCUTTING, "logs$", "^bird nest", "^clue nest");
		gather(Skill.MINING, " ore$", "^coal$", "^uncut ", "essence$", "^clay$", "^sandstone", "^granite",
			"^amethyst$", "^unidentified minerals$", "^gold nugget", "^volcanic ash$", "^calcified deposit$");
		gather(Skill.FISHING, "^raw ", "^leaping ", " eel$", "^karambwanji$", "^clue bottle", "^casket$");
		gather(Skill.HUNTER, "chinchompa$", " fur$", " hide$", "^bird nest", "^grimy ", "impling jar$", "^raw ",
			"^kebbit ", "feather$", "^hunters' loot sack", "^clue nest");
		RULES.put(Skill.FARMING, new Rule(Mode.GATHER, SkillingRules::isHarvest));
		RULES.put(Skill.THIEVING, new Rule(Mode.GATHER, name -> true));
		for (Skill s : new Skill[]{Skill.COOKING, Skill.CRAFTING, Skill.FLETCHING, Skill.HERBLORE, Skill.SMITHING,
			Skill.RUNECRAFT, Skill.MAGIC})
		{
			RULES.put(s, new Rule(Mode.PROCESS, name -> !BIRDHOUSE.matcher(name).find()));
		}
	}

	private static final Pattern NOT_HARVEST = Pattern.compile(
		"seed$|seedling|sapling$|compost|^bucket|plant pot$|^weeds$|^watering can|^empty ");

	private SkillingRules()
	{
	}

	private static void gather(Skill skill, String... patterns)
	{
		Pattern p = Pattern.compile(String.join("|", patterns));
		RULES.put(skill, new Rule(Mode.GATHER, name -> p.matcher(name).find()));
	}

	private static boolean isHarvest(String name)
	{
		return !NOT_HARVEST.matcher(name).find();
	}

	static boolean countsAsIncome(Skill skill)
	{
		return RULES.containsKey(skill);
	}

	/**
	 * Works out the income from one tick's item changes.
	 *
	 * @param skills skills that gained XP this tick, most XP first
	 * @return the income, or null if none of the skills made money from these changes
	 */
	static Income evaluate(List<Skill> skills, List<Change> changes)
	{
		for (Skill skill : skills)
		{
			Rule rule = RULES.get(skill);
			if (rule == null)
			{
				continue;
			}
			Income income = apply(skill, rule, changes);
			if (income != null)
			{
				return income;
			}
		}
		return null;
	}

	private static Income apply(Skill skill, Rule rule, List<Change> changes)
	{
		List<Change> products = new ArrayList<>();
		long gained = 0;
		long used = 0;
		boolean excluded = false;
		for (Change c : changes)
		{
			String name = c.getName() == null ? "" : c.getName().toLowerCase(Locale.ENGLISH);
			long value = c.getUnitPrice() * Math.abs(c.getQuantity());
			if (c.getQuantity() > 0)
			{
				if (rule.getProduct().test(name))
				{
					products.add(c);
					gained += value;
				}
				else if (rule.getMode() == Mode.PROCESS)
				{
					excluded = true;
				}
			}
			else
			{
				used += value;
			}
		}
		if (products.isEmpty() || excluded)
		{
			return null;
		}
		if (rule.getMode() == Mode.GATHER)
		{
			return new Income(skill, gained, products);
		}
		// Processing has to turn something into something else; gaining items alone isn't it
		return used == 0 ? null : new Income(skill, gained - used, products);
	}
}

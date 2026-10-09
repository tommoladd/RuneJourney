package com.runejourney;

import java.util.*;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import lombok.Value;
import net.runelite.api.Skill;

final class SkillingRules
{
	enum Mode
	{
		GATHER,
		PROCESS
	}

	@Value
	static class Change
	{
		String name;
		int quantity;
		long unitPrice;
	}

	@Value
	static class Income
	{
		Skill skill;
		long value;
		List<Change> products;
	}

	@Value
	private static class Rule
	{
		Mode mode;
		Predicate<String> product;
		Predicate<String> input;
	}

	private static final Map<Skill, Rule> RULES = new EnumMap<>(Skill.class);
	private static final Pattern BIRDHOUSE = Pattern.compile("bird ?house$");
	private static final Pattern SALVAGE = Pattern.compile("salvage$");

	static
	{
		gather(Skill.WOODCUTTING, "logs$", "^bird nest", "^clue nest");
		gather(Skill.MINING, " ore$", "^coal$", "^uncut ", "essence$", "^clay$", "^sandstone", "^granite",
			"^amethyst$", "^unidentified minerals$", "^gold nugget", "^volcanic ash$", "^calcified deposit$");
		gather(Skill.FISHING, "^raw ", "^leaping ", " eel$", "^karambwanji$", "^clue bottle", "^casket$");
		gather(Skill.HUNTER, "chinchompa$", " fur$", " hide$", "^bird nest", "^grimy ", "impling jar$", "^raw ",
			"^kebbit ", "feather$", "^hunters' loot sack", "^clue nest");
		RULES.put(Skill.FARMING, new Rule(Mode.GATHER, SkillingRules::isHarvest, null));
		RULES.put(Skill.THIEVING, new Rule(Mode.GATHER, name -> true, null));
		RULES.put(Skill.SAILING, new Rule(Mode.GATHER, name -> !SALVAGE.matcher(name).find(),
			name -> SALVAGE.matcher(name).find()));
		for (Skill s : new Skill[]{Skill.COOKING, Skill.CRAFTING, Skill.FLETCHING, Skill.HERBLORE, Skill.SMITHING,
			Skill.RUNECRAFT, Skill.MAGIC})
		{
			RULES.put(s, new Rule(Mode.PROCESS, name -> !BIRDHOUSE.matcher(name).find(), null));
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
		RULES.put(skill, new Rule(Mode.GATHER, name -> p.matcher(name).find(), null));
	}

	private static boolean isHarvest(String name)
	{
		return !NOT_HARVEST.matcher(name).find();
	}

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
		boolean consumed = false;
		boolean inputUsed = rule.getInput() == null;
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
				consumed = true;
				inputUsed |= rule.getInput() != null && rule.getInput().test(name);
			}
		}
		if (products.isEmpty() || excluded || !inputUsed)
		{
			return null;
		}
		if (rule.getMode() == Mode.GATHER)
		{
			return new Income(skill, gained, products);
		}
		return !consumed || gained == 0 ? null : new Income(skill, gained - used, products);
	}
}

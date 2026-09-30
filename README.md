# RuneJourney

Your OSRS account's personal history, goals and plans, recorded automatically while you play.

RuneJourney turns everyday play into a journey: it remembers what you've achieved, helps you decide
what to do next, and builds adaptive weekly plans towards your goals.

## Features

### Today
What you've done today (or this week, month, year...): time played, XP, levels, loot and skilling
income, boss kills, clues, collection log slots, combat tasks, net worth and your play streak.
Your day carries on across log-outs.

### My Goals
- Skill levels and XP, total level, base levels, Max Cape
- Boss kill counts, clue scrolls, combat tasks, Combat Achievement points, quest points
- Collection log slots, or completing a collection log page
- Obtaining items (searchable, tick off automatically from drops, clues, the collection log and the Grand Exchange)
- Saving money, reaching a net worth, or buying an item (completes when you buy it on the Grand Exchange)
- Custom goals

Goals can have a target date and hours per week. RuneJourney shows whether you're on track, builds a
weekly plan from what's left, and rebalances it every Monday around what you actually did. Choose how
you like to train each skill and estimates follow that method's XP rates, then your own observed rates.

### My Journey
A timeline of your account: levels, XP milestones, kill count milestones, personal bests, valuable
drops, clue caskets, collection log slots, pets, quests, diaries, combat tasks, goals and records,
with optional screenshots. Add your own memories for anything, on any date.

### Advisor
Ideas for the time you have: skills behind your weekly plan, bosses and clues that fit (favouring
kill count milestones you're close to), and anything you're close to finishing.

### Reports & charts
A reports window with charts for any metric over any period, breakdowns by skill, boss or clue tier,
comparisons with the previous period and personal records. Export a full report as a web page,
or your daily data as CSV.

### RuneJourney Wrapped
Every Monday, relive last week as an animated, in-game "Wrapped" with its own theme and music.

### Overlay (optional)
Show a pinned goal, this week's plan, today's stats, your streak or net worth on screen.

## Privacy

RuneJourney works entirely on your computer. It makes **no network requests** and sends nothing to any
server. Your data is stored in `.runelite/plugin-data/runejourney/`, one folder per account.
Reports are only created when you export them.

Net worth and screenshots can be turned off in the plugin settings.

## Notes

- The bank is only visible to plugins while it's open, so banked coins and bank value update when you open it.
- The game doesn't share your Combat Achievement points total; enter it once when you create a CA points goal.
- Wrapped's music is original, composed on the fly in the style of OSRS's MIDI soundtrack and played with
  Java's built-in synthesizer. By default the game's music is paused while Wrapped plays and restored afterwards.

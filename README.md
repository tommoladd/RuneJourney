# RuneJourney

Your OSRS account's personal history, goals and plans, recorded automatically while you play.

RuneJourney turns everyday play into a journey: it remembers what you've achieved, helps you decide
what to do next, and builds adaptive weekly plans towards your goals.

## Features

### Today
What you've done today (or this week, month, year...): time played, XP, levels, loot by source,
skilling income, supplies used and profit, boss kills, clues, collection log slots, combat tasks,
net worth and your play streak. Your day carries on across log-outs.

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
with optional screenshots. Add your own memories for anything, on any date, and right-click any
entry to add a note to it. The Screenshots gallery shows every screenshot taken, so you can view,
save a copy of or delete them.

### Advisor
Ideas for the time you have: skills behind your weekly plan, bosses and clues that fit (favouring
kill count milestones you're close to), and anything you're close to finishing.

### Reports & charts
A reports window with charts for any metric over any period, breakdowns by skill, boss or clue tier,
comparisons with the previous period and personal records. A Loot tab ranks your bosses and
activities by total loot, loot per kill and GP per hour. Export a full report as a web page,
or your daily data as CSV.

### RuneJourney Wrapped
Every Monday, relive last week as an animated, in-game "Wrapped" with its own look each week.

### Encouragement
A friendly chat message every so much XP in a skill ("That's another 250k Agility XP down. Keep it
up!"), with plenty of variety. Each skill has its own gap: slower skills like Agility and Runecraft
cheer you on every 250k XP, faster ones like Crafting and Smithing every 1m. Change or turn them off
in the plugin settings.

### Overlay (optional)
Show a pinned goal, this week's plan, today's stats, your streak or net worth on screen.

### Cloud sync (optional)
Back up your journey and keep it in step on every PC you play on. Turn on **Cloud sync** in the
plugin settings, sign in on the RuneJourney website with Discord, make a key and paste it into the
side panel (click "Cloud sync" under the title). Each account asks once, on each PC, before it's
saved. One key covers all your accounts; make one per PC so you can revoke a lost laptop on its own.

- Days played on several PCs add up, and XP is never counted twice: logging in waits a moment for
  your other PCs' records before counting XP gained while away.
- Goals, notes and settings follow the latest change. Deleting something on one PC deletes it on all.
- Screenshots stay on your PC for now: cloud sync keeps your journey only.
- Already have a journey on this PC and in the cloud? Choose to use the cloud's (this PC's is backed
  up first) or combine both.
- Optionally make an account's journey public, at its own page on the RuneJourney website: your
  character in 3D, levels, kills, timeline, goals and records. Notes, memories and net worth stay
  hidden unless you show them, and you choose whether it appears in the website's search.
  The character is copied while you stand still, each time you wear something new. Boss kills and
  clues are topped up from the official hiscores, and choosing **RuneJourney** in the collection
  log's menu (top left of the log) puts your whole collection log on the page. Every quest (finished,
  started or not started) and combat task (done or not) goes with it, read from the game as you play.

## Privacy

Without cloud sync, RuneJourney works entirely on your computer: it makes **no network requests** and
sends nothing to any server. Your data is stored in `.runelite/plugin-data/runejourney/`, one folder
per account. Reports are only created when you export them.

Cloud sync is off unless you turn it on. When it's on:
- Everything is **encrypted on your PC** before it's uploaded, and the cloud never receives your
  account name (RSN) or account hash. Accounts are matched between your PCs by a one-way fingerprint.
- The one exception is an account you make public: its page shows its RuneScape name and the parts of
  its journey you chose, unencrypted, for anyone to see. Make it private again and the page is deleted.
- RuneJourney's server and its storage provider see your IP address.
- The plugin only ever connects to the RuneJourney website (and, for public pages, the official
  hiscores through RuneLite). Every file goes to and from the website, which stores it.
- Your key is kept in `plugin-data/runejourney/cloud/`, not in RuneLite's settings.
- What sync does is logged in `plugin-data/runejourney/cloud/sync.log` (open it from the side panel):
  each request and how it went, never your key or a download address.
- Delete one account's cloud data, or everything, on the website, signed in with Discord.

Net worth and screenshots can be turned off in the plugin settings.

## Notes

- The bank is only visible to plugins while it's open. RuneJourney remembers what was in it (along with
  your potion storage, inventory, equipment, looting bag, seed vault and Grand Exchange offers) and
  re-prices it at current GE prices each time you log in, so open your bank once after installing.
- XP gained while RuneJourney isn't running (on mobile, say) is only seen when you next log in. If you
  last played on an earlier day, RuneJourney can't tell which day it was gained, so it isn't added to
  today; it's counted in any period that covers the whole gap, such as this month.
- The game doesn't share your Combat Achievement points total; enter it once when you create a CA points goal.

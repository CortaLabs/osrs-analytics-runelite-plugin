/*
 * Copyright (c) 2025, Corta Labs
 * BSD 2-Clause License. See LICENSE.
 */
package com.cortalabs.osrs.analytics.collector;

import com.cortalabs.osrs.analytics.collector.SignalEventCollector.CounterCoalescer;
import com.cortalabs.osrs.analytics.collector.SignalEventCollector.ParsedSignal;
import com.cortalabs.osrs.analytics.collector.SignalEventCollector.SignalSessionState;
import com.cortalabs.osrs.analytics.dto.SignalEvent;
import com.cortalabs.osrs.analytics.dto.SignalEvent.SignalType;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Guards {@link SignalEventCollector#parseSignal} (the witnessed-only chat taxonomy,
 * I2 — a line that only half-matches emits nothing, never a fabricated signal) and
 * {@link SignalSessionState} (same-tick re-render dedup, the per-session budget cap,
 * and the account-switch clear, I3/I4). All with plain strings, no live client.
 */
public class SignalEventCollectorTest
{
	private static ParsedSignal parse(String message)
	{
		return SignalEventCollector.parseSignal(message);
	}

	// --- Witnessed-only taxonomy: each pattern classifies its line, and only its line ---

	@Test
	public void levelUpParsesSkillAndTheNewLevelWhenTheMessageCarriesIt()
	{
		// The game's actual text (RuneLite ScreenshotPlugin's LEVEL_UP pattern).
		ParsedSignal p = parse("Congratulations, you've just advanced your Agility level. You are now level 88.");
		assertEquals(SignalType.LEVEL_UP, p.signalType);
		assertEquals("Agility", p.subject);
		assertEquals(Integer.valueOf(88), p.value);
		ParsedSignal curly = parse("Congratulations, you’ve just advanced your Cooking level. You are now level 43.");
		assertEquals(SignalType.LEVEL_UP, curly.signalType);
		assertEquals(Integer.valueOf(43), curly.value);
		// The historic phrasing stays tolerated.
		assertEquals(SignalType.LEVEL_UP,
			parse("Congratulations, you just advanced your Cooking level. You are now level 43.").signalType);
	}

	@Test
	public void levelUpValueIsAbsentWhenTheLineHasNoLevelNumber()
	{
		// The split-message form carries only the skill; the level is honestly absent, not guessed.
		ParsedSignal p = parse("Congratulations, you just advanced your Attack level.");
		assertEquals(SignalType.LEVEL_UP, p.signalType);
		assertEquals("Attack", p.subject);
		assertNull("no level in the line => absent value, never a default", p.value);
	}

	@Test
	public void petLinesParseWithNoSubjectOrValue()
	{
		ParsedSignal p = parse("You have a funny feeling like you're being followed.");
		assertEquals(SignalType.PET, p.signalType);
		assertNull(p.subject);
		assertNull(p.value);
		assertEquals(SignalType.PET, parse("You feel something weird sneaking into your backpack.").signalType);
	}

	@Test
	public void petMissLineEmitsNothing()
	{
		// "...would have been followed" means the pet rolled but was NOT received (full inventory).
		// Signalling a pet here would fabricate an acquisition that never happened.
		assertNull("a rolled-but-lost pet must not signal an acquisition",
			parse("You have a funny feeling like you would have been followed."));
	}

	@Test
	public void clueCompletionParsesTierAndCountIncludingThousandsSeparators()
	{
		ParsedSignal p = parse("You have completed 42 medium Treasure Trails.");
		assertEquals(SignalType.CLUE_COMPLETION, p.signalType);
		assertEquals("medium", p.subject);
		assertEquals(Integer.valueOf(42), p.value);
		assertEquals(Integer.valueOf(1234), parse("You have completed 1,234 easy Treasure Trails.").value);
	}

	@Test
	public void bossKcParsesBossAndCountFromTheChatCommandsCorpusWithTagsStripped()
	{
		ParsedSignal p = parse("Your <col=ff0000>Zulrah</col> kill count is: <col=ff0000>1,412</col>");
		assertEquals(SignalType.BOSS_KC, p.signalType);
		assertEquals("Zulrah", p.subject);
		assertEquals(Integer.valueOf(1412), p.value);
	}

	@Test
	public void countersAreTypedByWhatTheyCountAndOnlyKillsAreKillCounts()
	{
		ParsedSignal lap = parse("Your Rellekka Rooftop lap count is: 3281.");
		assertEquals(SignalType.LAP_COUNT, lap.signalType);
		assertEquals("Rellekka Rooftop", lap.subject);
		assertEquals(Integer.valueOf(3281), lap.value);
		assertEquals(SignalType.HARVEST_COUNT, parse("Your Zalcano harvest count is: 50").signalType);
		ParsedSignal chest = parse("Your Barrows chest count is: <col=ff0000>10</col>.");
		assertEquals(SignalType.COMPLETION_COUNT, chest.signalType);
		assertEquals("Barrows chest", chest.subject);
		assertEquals(Integer.valueOf(10), chest.value);
		ParsedSignal raid = parse("Your completed Theatre of Blood count is: 5.");
		assertEquals(SignalType.COMPLETION_COUNT, raid.signalType);
		assertEquals("Theatre of Blood", raid.subject);
		ParsedSignal toa = parse("Your completion count for Tombs of Amascut is: 3.");
		assertEquals(SignalType.COMPLETION_COUNT, toa.signalType);
		assertEquals("Tombs of Amascut", toa.subject);
		assertEquals(SignalType.COMPLETION_COUNT, parse("Your subdued Wintertodt count is: 50.").signalType);
		assertEquals(SignalType.COMPLETION_COUNT, parse("Your Gauntlet completion count is: 12.").signalType);
		assertNull("a bare 'is:' without a count is not a counter", parse("Your reward is: 5"));
	}

	// --- Counter coalescing ---

	private static SignalEvent counter(SignalType type, String subject, int value, String at)
	{
		SignalEvent e = new SignalEvent();
		e.signalType = type;
		e.subject = subject;
		e.value = value;
		e.timestamp = at;
		return e;
	}

	@Test
	public void firstCounterValuePerSessionIsSentAtOnceThenCoalescedPerWindow()
	{
		CounterCoalescer c = new CounterCoalescer();
		long t0 = 1_000_000L;
		assertEquals(1, c.offer(1L, t0, counter(SignalType.LAP_COUNT, "Rellekka Rooftop", 100, "t0")).size());
		// Laps inside the window are held, never sent one-per-lap.
		assertTrue(c.offer(1L, t0 + 60_000, counter(SignalType.LAP_COUNT, "Rellekka Rooftop", 101, "t1")).isEmpty());
		assertTrue(c.offer(1L, t0 + 120_000, counter(SignalType.LAP_COUNT, "Rellekka Rooftop", 102, "t2")).isEmpty());
		assertTrue(c.hasPending());
		assertTrue(c.due(1L, t0 + CounterCoalescer.WINDOW_MS - 1).isEmpty());
		List<SignalEvent> due = c.due(1L, t0 + CounterCoalescer.WINDOW_MS);
		assertEquals(1, due.size());
		assertEquals(Integer.valueOf(102), due.get(0).value);
		assertEquals("the witnessed line's own timestamp, not the flush time", "t2", due.get(0).timestamp);
		assertFalse(c.hasPending());
	}

	@Test
	public void sessionEndFlushesTheLatestValueAndUnchangedValuesAreNeverResent()
	{
		CounterCoalescer c = new CounterCoalescer();
		c.offer(1L, 0L, counter(SignalType.COMPLETION_COUNT, "Barrows chest", 10, "a"));
		assertTrue(c.offer(1L, 1_000L, counter(SignalType.COMPLETION_COUNT, "Barrows chest", 10, "b")).isEmpty());
		assertFalse("an unchanged counter is not pending", c.hasPending());
		c.offer(1L, 2_000L, counter(SignalType.COMPLETION_COUNT, "Barrows chest", 11, "c"));
		List<SignalEvent> flushed = c.endSession();
		assertEquals(1, flushed.size());
		assertEquals(Integer.valueOf(11), flushed.get(0).value);
		// A new session starts clean: its first value is sent at once again.
		assertEquals(1, c.offer(1L, 3_000L, counter(SignalType.COMPLETION_COUNT, "Barrows chest", 11, "d")).size());
	}

	@Test
	public void accountChangeDropsTheOtherAccountsPendingCounters()
	{
		CounterCoalescer c = new CounterCoalescer();
		c.offer(1L, 0L, counter(SignalType.LAP_COUNT, "Canifis Rooftop", 5, "a"));
		c.offer(1L, 1_000L, counter(SignalType.LAP_COUNT, "Canifis Rooftop", 6, "b"));
		assertEquals("account B's first value is not throttled by A",
			1, c.offer(2L, 2_000L, counter(SignalType.LAP_COUNT, "Canifis Rooftop", 1, "c")).size());
		assertTrue("A's pending value never bleeds into B", c.endSession().isEmpty());
	}

	@Test
	public void diaryCompletionParsesTheRegionAndToleratesPhrasing()
	{
		ParsedSignal p = parse("Congratulations! You have completed all of the medium tasks in the Ardougne area.");
		assertEquals(SignalType.DIARY_COMPLETION, p.signalType);
		assertEquals("Ardougne", p.subject);
		// Tolerant of "all the" and a line with no "area" suffix.
		assertEquals(SignalType.DIARY_COMPLETION,
			parse("You have completed all the hard tasks in the Wilderness.").signalType);
	}

	@Test
	public void nonSignalLinesEmitNothing()
	{
		assertNull(parse("Zezima: hey what's up"));
		assertNull(parse("You have 5 more casks to open."));
		assertNull(parse(""));
		assertNull(parse(null));
	}

	@Test
	public void ambiguousHalfMatchesEmitNothing()
	{
		// "count is:" without the "Your <boss> (kill|...)" frame is not a boss KC.
		assertNull(parse("The current count is: 5"));
		// A bare mention of a level is not a level-up advance.
		assertNull(parse("Your combat level is now higher."));
		// "completed the quest" is not a diary "completed all the ... tasks in ..." line.
		assertNull(parse("You are close to completing the tasks."));
	}

	@Test
	public void questCompletionIsDeliberatelyNotChatMatched()
	{
		// OSRS marks a quest complete via a completion widget, not a reliable game message;
		// quest-completion provenance is owned by the quest-v2 lane. The enum value exists for
		// wire parity, but fabricating a fragile chat regex would break the witnessed-only rule.
		assertNull(parse("You have completed the quest Cook's Assistant."));
	}

	@Test
	public void detailCarriesTheWitnessedLineVerbatim()
	{
		ParsedSignal p = parse("Congratulations, you just advanced your Fishing level.");
		assertEquals("Congratulations, you just advanced your Fishing level.", p.detail);
	}

	// --- Dedup + per-session budget + account-switch (I3/I4) ---

	@Test
	public void sameTickRepeatIsSuppressedButALaterTickRepeatEmits()
	{
		SignalSessionState s = new SignalSessionState();
		assertTrue(s.admit(1L, 100, "advanced your Cooking level"));
		assertFalse("a re-rendered identical line on the same tick must not double-fire",
			s.admit(1L, 100, "advanced your Cooking level"));
		assertTrue("a genuine repeat on a later tick still emits",
			s.admit(1L, 101, "advanced your Cooking level"));
	}

	@Test
	public void differentLinesOnTheSameTickBothEmit()
	{
		SignalSessionState s = new SignalSessionState();
		assertTrue(s.admit(1L, 100, "line A"));
		assertTrue(s.admit(1L, 100, "line B"));
	}

	@Test
	public void perSessionCapCeilingHolds()
	{
		// Mutation-proof budget: past the cap, nothing more emits (break the cap check -> extra rows).
		SignalSessionState s = new SignalSessionState(3);
		assertTrue(s.admit(1L, 1, "a"));
		assertTrue(s.admit(1L, 2, "b"));
		assertTrue(s.admit(1L, 3, "c"));
		assertFalse("past the per-session cap, no more signals emit", s.admit(1L, 4, "d"));
		assertFalse(s.admit(1L, 5, "e"));
	}

	@Test
	public void accountSwitchClearsDedupAndBudget()
	{
		SignalSessionState s = new SignalSessionState(2);
		assertTrue(s.admit(100L, 1, "x"));
		assertTrue(s.admit(100L, 2, "y"));
		assertFalse("account A has hit its cap", s.admit(100L, 3, "z"));
		// Account B logs in on the same client: its budget and dedup start fresh.
		assertTrue("account B must not inherit account A's exhausted budget", s.admit(200L, 3, "x"));
		assertTrue(s.admit(200L, 4, "y"));
	}

	@Test
	public void endSessionResetsTheBudget()
	{
		SignalSessionState s = new SignalSessionState(1);
		assertTrue(s.admit(1L, 1, "a"));
		assertFalse(s.admit(1L, 2, "b"));
		s.endSession();
		assertTrue("a new session gets a fresh budget", s.admit(1L, 3, "c"));
	}
}

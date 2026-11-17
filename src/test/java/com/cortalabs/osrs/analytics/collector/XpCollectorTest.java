/*
 * Copyright (c) 2025, Corta Labs
 * BSD 2-Clause License. See LICENSE.
 */
package com.cortalabs.osrs.analytics.collector;

import com.cortalabs.osrs.analytics.collector.XpCollector.XpCoalescer;
import com.cortalabs.osrs.analytics.dto.XpSnapshot;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

/**
 * Guards the 1.10.0 XP cadence: a report must always be able to bracket its window with
 * two distinct client observations, without one snapshot per XP drop.
 */
public class XpCollectorTest
{
	private static XpSnapshot xp(int agility, String timestamp)
	{
		Map<String, Integer> skills = new LinkedHashMap<>();
		skills.put("agility", agility);
		skills.put("cooking", 10);
		XpSnapshot s = new XpSnapshot();
		s.skills = skills;
		s.timestamp = timestamp;
		return s;
	}

	@Test
	public void firstSnapshotIsSentThenChangesAreCoalescedToTheLatest()
	{
		XpCoalescer c = new XpCoalescer();
		long t0 = 1_000_000L;
		XpSnapshot first = xp(100, "t0");
		assertSame(first, c.offer(1L, t0, first));
		assertNull("held inside the window", c.offer(1L, t0 + 10_000, xp(180, "t1")));
		XpSnapshot latest = xp(260, "t2");
		assertNull(c.offer(1L, t0 + 20_000, latest));
		assertNull(c.due(1L, t0 + XpCoalescer.WINDOW_MS - 1));
		assertSame("the latest pending snapshot, with its own timestamp", latest, c.due(1L, t0 + XpCoalescer.WINDOW_MS));
		assertNull(c.due(1L, t0 + 2 * XpCoalescer.WINDOW_MS));
		XpSnapshot later = xp(340, "t3");
		assertSame("a change after the window is sent at once", later, c.offer(1L, t0 + 3 * XpCoalescer.WINDOW_MS, later));
	}

	@Test
	public void unchangedXpIsNeverResent()
	{
		XpCoalescer c = new XpCoalescer();
		c.offer(1L, 0L, xp(100, "t0"));
		assertNull(c.offer(1L, XpCoalescer.WINDOW_MS * 5, xp(100, "t1")));
		assertNull(c.offer(1L, 1_000L, xp(150, "t2")));
		assertNull("back to the sent value: the pending change is dropped", c.offer(1L, 2_000L, xp(100, "t3")));
		assertNull(c.due(1L, XpCoalescer.WINDOW_MS * 10));
	}

	@Test
	public void sessionEndFlushesThePendingSnapshotAndAccountSwitchStartsClean()
	{
		XpCoalescer c = new XpCoalescer();
		c.offer(1L, 0L, xp(100, "t0"));
		XpSnapshot pending = xp(200, "t1");
		c.offer(1L, 1_000L, pending);
		assertSame(pending, c.endSession());
		assertNull(c.endSession());
		XpSnapshot next = xp(200, "t2");
		assertSame("a new session's first observation is sent even if unchanged", next, c.offer(1L, 2_000L, next));
		XpSnapshot other = xp(5, "t3");
		assertSame("another account never inherits this one's window", other, c.offer(2L, 3_000L, other));
	}
}

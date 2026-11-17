/*
 * Copyright (c) 2025, Corta Labs
 * BSD 2-Clause License. See LICENSE.
 */
package com.cortalabs.osrs.analytics.collector;

import com.cortalabs.osrs.analytics.collector.EfficiencyCollector.EfficiencyAccumulator;
import com.cortalabs.osrs.analytics.collector.RegionTimeShareCollector.RegionAccumulator;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

/** 1.10.0: every session-scoped lane carries the same login session id. */
public class SessionContextTest
{
	@Test
	public void sessionScopedCollectorsShareOneIdUntilTheSessionEnds()
	{
		SessionContext sessions = new SessionContext();
		EfficiencyAccumulator efficiency = new EfficiencyAccumulator(sessions::current);
		RegionAccumulator regions = new RegionAccumulator(sessions::current);
		efficiency.recordTick(0, 0, 420, "Zezima");
		regions.recordTick(12850, "Zezima");
		assertEquals(efficiency.sessionId(), regions.sessionId());

		String first = efficiency.sessionId();
		sessions.end();
		efficiency.clear();
		regions.clear();
		regions.recordTick(12850, "Zezima");
		efficiency.recordTick(0, 0, 420, "Zezima");
		assertEquals(efficiency.sessionId(), regions.sessionId());
		assertNotEquals("a new login is a new session", first, efficiency.sessionId());
	}
}

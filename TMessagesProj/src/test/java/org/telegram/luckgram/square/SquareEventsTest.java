package org.telegram.luckgram.square;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/** luckgram-specs#115: impressions once per load, views with dwell, batching, retry once, no text. */
public class SquareEventsTest {

    private static Map<String, Integer> vis(Object... idRank) {
        HashMap<String, Integer> m = new HashMap<>();
        for (int i = 0; i < idRank.length; i += 2) m.put((String) idRank[i], (Integer) idRank[i + 1]);
        return m;
    }

    @Test
    public void impressionsOncePerLoadAndViews() {
        SquareEvents.Tracker t = new SquareEvents.Tracker("recommend");
        List<SquareEvents.Event> e = t.update(vis("a", 0, "b", 1), 1000);
        assertEquals(2, e.size());
        for (SquareEvents.Event x : e) assertEquals(SquareEvents.IMPRESSION, x.type);
        assertTrue(t.update(vis("a", 0, "b", 1), 2000).isEmpty());
        e = t.update(vis("b", 1), 4500); // a left after 3.5 s
        assertEquals(1, e.size());
        assertEquals(SquareEvents.VIEW, e.get(0).type);
        assertEquals("a", e.get(0).postId);
        assertEquals(3500, e.get(0).dwellMs);
        assertEquals(0, e.get(0).rank);
        e = t.update(vis("a", 0, "b", 1), 5000); // a back: no second impression in this load
        assertTrue(e.isEmpty());
        e = t.hideAll(5600); // short views are reported too
        assertEquals(2, e.size());
        t.reset();
        assertEquals(1, t.update(vis("a", 0), 6000).size());
    }

    @Test
    public void dwellIsCapped() {
        SquareEvents.Tracker t = new SquareEvents.Tracker("hot");
        t.update(vis("a", 0), 0);
        assertEquals(SquareEvents.MAX_DWELL_MS, t.hideAll(60L * 60 * 1000).get(0).dwellMs);
    }

    @Test
    public void jsonHasOnlyContractFields() throws Exception {
        JSONObject o = new SquareEvents.Event(SquareEvents.VIEW, "p1", "recommend", 3, 5400, 0).toJson();
        assertEquals("view", o.getString("type"));
        assertEquals("p1", o.getString("post_id"));
        assertEquals("recommend", o.getString("tab"));
        assertEquals(3, o.getInt("rank"));
        assertEquals(5400, o.getLong("dwell_ms"));
        assertEquals("1970-01-01T00:00:00Z", o.getString("at"));
        assertEquals(6, o.length());
        JSONObject open = SquareEvents.Event.of(SquareEvents.OPEN, "p1", "hot", 0).toJson();
        assertFalse(open.has("dwell_ms"));
        assertFalse(open.has("rank"));
    }

    @Test
    public void queueBatchesAndPersists() throws Exception {
        long now = 1_800_000_000_000L;
        SquareEvents.Queue q = new SquareEvents.Queue(null, now);
        assertFalse(q.shouldFlush(now));
        for (int i = 0; i < 19; i++) q.add(SquareEvents.Event.of("open", "p" + i, "latest", now));
        assertFalse(q.shouldFlush(now + 1000));
        assertTrue(q.shouldFlush(now + SquareEvents.FLUSH_INTERVAL_MS));
        q.add(SquareEvents.Event.of("open", "p19", "latest", now));
        assertTrue(q.shouldFlush(now + 1000));
        for (int i = 0; i < 200; i++) q.add(SquareEvents.Event.of("open", "x" + i, "latest", now));
        JSONArray batch = q.nextBatch().getJSONArray("events");
        assertEquals(SquareEvents.BATCH_MAX, batch.length());
        q.onSent(batch.length(), now);
        assertEquals(120, q.size());
        SquareEvents.Queue back = new SquareEvents.Queue(q.toString(), now + 1000);
        assertEquals(120, back.size());
        // older than 24 h are dropped on load
        assertEquals(0, new SquareEvents.Queue(q.toString(), now + SquareEvents.MAX_AGE_MS + 1).size());
        assertNull(new SquareEvents.Queue("garbage", now).nextBatch());
    }

    @Test
    public void retryOnceThenDrop() {
        SquareEvents.Queue q = new SquareEvents.Queue(null, 0);
        q.add(SquareEvents.Event.of("open", "a", "latest", 0));
        q.onFailed(1, 0, 0); // offline: keep
        assertEquals(1, q.size());
        q.onFailed(1, 503, 0); // second failure: drop
        assertEquals(0, q.size());
        q.add(SquareEvents.Event.of("open", "b", "latest", 0));
        q.onFailed(1, 400, 0); // malformed: drop at once
        assertEquals(0, q.size());
    }

    @Test
    public void queueCap() {
        SquareEvents.Queue q = new SquareEvents.Queue(null, 0);
        for (int i = 0; i < SquareEvents.QUEUE_MAX + 10; i++) q.add(SquareEvents.Event.of("open", "p" + i, "latest", 0));
        assertEquals(SquareEvents.QUEUE_MAX, q.size());
    }

    @Test
    public void recommendFeedPath() {
        assertEquals("/v1/square/feed?tab=recommend", SquareLogic.feedPath(SquareLogic.TAB_RECOMMEND, null));
    }
}

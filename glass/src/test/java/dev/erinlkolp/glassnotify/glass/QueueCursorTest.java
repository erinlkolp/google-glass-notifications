package dev.erinlkolp.glassnotify.glass;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

public class QueueCursorTest {

    private QueueCursor cursor;

    @Before
    public void setUp() {
        cursor = new QueueCursor();
    }

    @Test
    public void startsEmpty() {
        assertTrue(cursor.isEmpty());
        assertEquals(0, cursor.size());
        assertEquals(0, cursor.index());
    }

    @Test
    public void movesForwardAndBackward() {
        cursor.setSize(3);

        assertEquals(0, cursor.index());
        assertTrue(cursor.next());
        assertEquals(1, cursor.index());
        assertTrue(cursor.next());
        assertEquals(2, cursor.index());
        assertTrue(cursor.previous());
        assertEquals(1, cursor.index());
    }

    @Test
    public void doesNotWrapAtEitherEnd() {
        // Wrapping on a head-mounted display is disorienting - you lose track
        // of whether you have seen everything. Stop at the ends instead.
        cursor.setSize(2);

        assertFalse(cursor.previous());
        assertEquals(0, cursor.index());

        cursor.next();
        assertFalse(cursor.next());
        assertEquals(1, cursor.index());
    }

    @Test
    public void clampsWhenTheListShrinksUnderTheReader() {
        // THE case that full-state snapshots make routine: reading item 5 of 7
        // when a snapshot arrives holding only 3. Spec section 12.3.
        cursor.setSize(7);
        cursor.next();
        cursor.next();
        cursor.next();
        cursor.next();
        assertEquals(4, cursor.index());

        cursor.setSize(3);

        assertEquals("must clamp to the last valid index, not throw", 2, cursor.index());
    }

    @Test
    public void clampsToZeroWhenEverythingIsDismissed() {
        cursor.setSize(5);
        cursor.next();
        cursor.next();

        cursor.setSize(0);

        assertEquals(0, cursor.index());
        assertTrue(cursor.isEmpty());
    }

    @Test
    public void holdsPositionWhenTheListGrows() {
        // New notifications arrive at the head, but the reader's position is
        // an index. Growing the list must not silently move what they are reading.
        cursor.setSize(3);
        cursor.next();
        assertEquals(1, cursor.index());

        cursor.setSize(9);

        assertEquals(1, cursor.index());
    }

    @Test
    public void navigationOnAnEmptyQueueIsANoOp() {
        assertFalse(cursor.next());
        assertFalse(cursor.previous());
        assertEquals(0, cursor.index());
    }

    @Test
    public void rejectsANegativeSize() {
        try {
            cursor.setSize(-1);
            org.junit.Assert.fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
        }
    }
}

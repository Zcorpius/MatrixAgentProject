package com.matrix.agent.launcher.overlay;
import static org.junit.Assert.*;
import org.junit.Test;

public final class RevealTicketTest {
    @Test public void bothEventsRequiredInEitherOrder() {
        RevealTicket ticket = new RevealTicket("request", 3, 10_000);
        assertFalse(ticket.canReveal(100, false));
        ticket.dispatched(200);
        assertFalse(ticket.canReveal(300, true)); assertTrue(ticket.canReveal(300, false));
        assertTrue(ticket.matches("request", 3)); assertFalse(ticket.matches("request", 4));
    }
    @Test public void lateDepartureAndOperationDeadlineCannotReveal() {
        RevealTicket ticket = new RevealTicket("request", 3, 1500);
        ticket.dispatched(100);
        assertTrue(ticket.expired(1500)); assertFalse(ticket.canReveal(1500, false));
        RevealTicket another = new RevealTicket("request", 3, 10_000);
        another.dispatched(100);
        assertTrue(another.expired(2100)); assertFalse(another.canReveal(2100, false));
    }
    @Test public void missingDispatchExpiresAtOriginalOperationDeadline() {
        RevealTicket ticket = new RevealTicket("request", 3, 3000);
        assertFalse(ticket.expired(2999)); assertTrue(ticket.expired(3000));
    }
}

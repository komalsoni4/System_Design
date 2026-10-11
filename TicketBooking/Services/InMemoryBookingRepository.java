package Services;

import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.ConcurrentHashMap;

import Entities.Booking;

public class InMemoryBookingRepository implements BookingRepository {
    private final Map<String, Booking> table = new ConcurrentHashMap<>();

    @Override
    public void save(Booking booking) {
        table.put(booking.getBookingId(), booking);
    }

    @Override
    public Booking findById(String bookingId) {
        Booking booking = table.get(bookingId);
        if (booking == null) throw new NoSuchElementException("Booking not found: " + bookingId);
        return booking;
    }
}

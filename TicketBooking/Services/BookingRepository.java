package Services;

import Entities.Booking;

public interface BookingRepository {
	void save(Booking booking);
	Booking findById(String bookingId);
}

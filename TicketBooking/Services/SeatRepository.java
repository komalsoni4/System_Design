package Services;

import java.util.List;

import Entities.ShowSeat;

public interface SeatRepository {
	void addSeat(ShowSeat seat);
	ShowSeat find(String showId, String seatNo);
	List<ShowSeat> findByShow(String showId);
	boolean tryHold(String showId, String seatNo, String bookingId, long now, long expiresAt);
	boolean confirm(String showId, String seatNo, String bookingId, long now);
	void release(String showId, String seatNo, String bookingId);
	int releaseExpired(long now);
}

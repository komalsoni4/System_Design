package Services;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import Entities.ShowSeat;

public class InMemorySeatRepository implements SeatRepository {
    private final Map<String, Map<String, ShowSeat>> table = new ConcurrentHashMap<>();

    @Override
    public void addSeat(ShowSeat seat) {
        table.computeIfAbsent(seat.getShowId(), key -> new ConcurrentHashMap<>())
             .put(seat.getSeatNo(), seat);
    }

    @Override
    public ShowSeat find(String showId, String seatNo) {
        Map<String, ShowSeat> seats = table.get(showId);
        ShowSeat seat = seats == null ? null : seats.get(seatNo);
        if (seat == null) throw new IllegalArgumentException("No such seat: " + seatNo);
        return seat;
    }

    @Override
    public List<ShowSeat> findByShow(String showId) {
        Map<String, ShowSeat> seats = table.get(showId);
        if (seats == null) return new ArrayList<>();
        return new ArrayList<>(seats.values());
    }

    @Override
    public boolean tryHold(String showId, String seatNo, String bookingId, long now, long expiresAt) {
        return find(showId, seatNo).tryHold(bookingId, now, expiresAt);
    }

    @Override
    public boolean confirm(String showId, String seatNo, String bookingId, long now) {
        return find(showId, seatNo).confirm(bookingId, now);
    }

    @Override
    public void release(String showId, String seatNo, String bookingId) {
        find(showId, seatNo).release(bookingId);
    }

    @Override
    public int releaseExpired(long now) {
        int count = 0;
        for (Map<String, ShowSeat> seats : table.values()) {
            for (ShowSeat seat : seats.values()) {
                if (seat.releaseIfExpired(now)) count++;
            }
        }
        return count;
    }
}

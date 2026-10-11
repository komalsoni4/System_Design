package Entities;

public class ShowSeat {
    private String seatNo;
    private String showId;
    private final double price;
    private SeatStatus status = SeatStatus.AVAILABLE;
    private String bookingId;
    private long holdExpireAt;

    public ShowSeat(String seatNo, String showId, double price) {
        this.seatNo = seatNo;
        this.showId = showId;
        this.price = price;
    }

    public String getSeatNo() {
        return seatNo;
    }

    public String getShowId() {
        return showId;
    }
     public double getPrice() {
        return price;
     }
    public synchronized SeatStatus getStatus() { return status; }
    public synchronized String getBookingId() { return bookingId; }
    // Every method that changes state is synchronized, so "check + change" happens as ONE step.
    // In a real database this is the single statement:
    //   UPDATE show_seat SET status='HELD', ... WHERE seat=? AND (status='AVAILABLE' OR hold expired)
    public synchronized boolean tryHold(String bookingId, long now, long expiresAt) {
        boolean free = status == SeatStatus.AVAILABLE
                    || (status == SeatStatus.HELD && now > holdExpireAt);   // old hold ran out
        if (!free) return false;
        this.status = SeatStatus.HELD;
        this.bookingId = bookingId;
        this.holdExpireAt = expiresAt;
        return true;
    }
    public synchronized boolean confirm(String bookingId, long now) {
        if (status != SeatStatus.HELD || !bookingId.equals(this.bookingId) ||  now > holdExpireAt) return false;
        this.status = SeatStatus.BOOKED;
        return true;
    }
    // Give the seat back, but only if it still belongs to this booking
    public synchronized boolean release(String bookingId) {
        if (status != SeatStatus.AVAILABLE && bookingId.equals(this.bookingId)) {
            this.status = SeatStatus.AVAILABLE;
            this.bookingId = null;
            return true;
        }
        return false;
    }
   
    // Used by the background cleanup job
    public synchronized boolean releaseIfExpired(long now) {
        if (status == SeatStatus.HELD && now > holdExpireAt) {
            this.status = SeatStatus.AVAILABLE;
            this.bookingId = null;
            return true;
        }
        return false;
    }


}

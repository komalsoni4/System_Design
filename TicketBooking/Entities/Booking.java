package Entities;

import java.util.List;

public class Booking {
    private final String bookingId;
    private final String userId;        // points to User
    private final String showId;        // points to Show
    private final List<String> seatNos;
    private final double totalAmount;
    private final long createdAt;
    private final long holdExpiresAt;
    private volatile BookingStatus status = BookingStatus.PENDING;
    private volatile Payment payment;

     public Booking(String bookingId, String userId, String showId, List<String> seatNos,
                   double totalAmount, long createdAt, long holdExpiresAt) {
        this.bookingId = bookingId;
        this.userId = userId;
        this.showId = showId;
        this.seatNos = seatNos;
        this.totalAmount = totalAmount;
        this.createdAt = createdAt;
        this.holdExpiresAt = holdExpiresAt;
    }
     public boolean isExpired(long now) { return now > holdExpiresAt; }

    public String getBookingId() { return bookingId; }
    public String getUserId() { return userId; }
    public String getShowId() { return showId; }
    public List<String> getSeatNos() { return seatNos; }
    public double getTotalAmount() { return totalAmount; }
    public long getCreatedAt() { return createdAt; }
    public long getHoldExpiresAt() { return holdExpiresAt; }
    public BookingStatus getStatus() { return status; }
    public void setStatus(BookingStatus status) { this.status = status; }
    public Payment getPayment() { return payment; }
    public void setPayment(Payment payment) { this.payment = payment; }
}

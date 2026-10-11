package Entities;

public class Payment {
    private final String paymentId;
    private final String bookingId;     // points to Booking
    private final double amount;
    private final PaymentStatus status;
    private final String idempotencyKey;

    public Payment(String paymentId, String bookingId, double amount,
                   PaymentStatus status, String idempotencyKey) {
        this.paymentId = paymentId;
        this.bookingId = bookingId;
        this.amount = amount;
        this.status = status;
        this.idempotencyKey = idempotencyKey;
    }
    public String getPaymentId() { return paymentId; }
    public String getBookingId() { return bookingId; }
    public double getAmount() { return amount; }
    public PaymentStatus getStatus() { return status; }
    public String getIdempotencyKey() { return idempotencyKey; }
}

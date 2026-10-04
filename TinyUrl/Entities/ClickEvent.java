package Entities;
import java.time.LocalDateTime;
public class ClickEvent {
    private final String eventId;
    private final String shortKey;          // which short url was clicked (links to ShortUrl)
    private final LocalDateTime clickedAt;
    private final String country;
    private final String device;
    private final String referrer;

    public ClickEvent(String eventId, String shortKey, String country, String device, String referrer) {
        this.eventId = eventId;
        this.shortKey = shortKey;
        this.clickedAt = LocalDateTime.now();
        this.country = country;
        this.device = device;
        this.referrer = referrer;
    }

    public String getEventId() { return eventId; }
    public String getShortKey() { return shortKey; }
    public LocalDateTime getClickedAt() { return clickedAt; }
    public String getCountry() { return country; }
    public String getDevice() { return device; }
    public String getReferrer() { return referrer; }
}

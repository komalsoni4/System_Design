package Entities;
public class Show {
private String showId;
private String EventId;
private String showTime;
private String showDate;
public Show(String showId, String EventId, String showTime, String showDate) {
    this.showId = showId;
    this.EventId = EventId;
    this.showTime = showTime;
    this.showDate = showDate;
}
public String getShowId() {
    return showId;
}
public String getEventId() {
    return EventId;
}
public String getShowTime() {
    return showTime;
}
public String getShowDate() {
    return showDate;
}
}
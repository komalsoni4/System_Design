package Entities;

public class Concert implements Event {
    private String concertId;
    private String name;
    private String description; // in format YYYY-MM-DD
   

    public Concert(String concertId, String name, String description) {
        this.concertId = concertId;
        this.name = name;
        this.description = description;
    }

    public String getConcertId() {
        return concertId;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

}

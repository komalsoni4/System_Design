package Entities;

public class Match implements Event {
    private String matchId;
    private String name;  
    private String description;

    public Match(String matchId, String name, String description) {
        this.matchId = matchId;
        this.name = name;
        this.description = description;
    }

    public String getMatchId() {
        return matchId;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

}

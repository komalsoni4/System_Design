package Entities;

public class Movie implements Event {
    private String movieId;
    private String title;
    private String description;

    public Movie(String movieId, String title, String description) {
        this.movieId = movieId;
        this.title = title;
        this.description = description;
    }
     
    public String getMovieId() {
        return movieId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }
}

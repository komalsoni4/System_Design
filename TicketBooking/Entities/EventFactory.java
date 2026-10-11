package Entities;

public class EventFactory {
    
    public static Event createEvent(String category, String id, String name, String description) {
        if (category.equalsIgnoreCase("movie")) {
          
            return new Movie(id, name, description); 
        } else if (category.equalsIgnoreCase("concert")) {
            return new Concert(id, name, description); 
        } else if (category.equalsIgnoreCase("match")) {
            return new Match(id, name, description);
        }
        return null;
    }
}

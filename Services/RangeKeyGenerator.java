package Services;

import Entities.KeyRange;

public class RangeKeyGenerator implements KeyGenerator {
   private final KeyRangeAllocator allocator;
    private KeyRange currentRange;

    public RangeKeyGenerator(KeyRangeAllocator allocator) {
        this.allocator = allocator;
    }

    @Override 
    public String getNextKey() {
        // Implementation for generating the next key in a range
        if (currentRange == null || !currentRange.hasNext()) {
            currentRange = allocator.allocateRange();   // range finished, ask for a new one
        }
        return Base62.encode(currentRange.next());
    }
}

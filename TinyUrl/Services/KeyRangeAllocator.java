package Services;

import Entities.KeyRange;

public class KeyRangeAllocator {
    private final long rangeSize;
    private long nextStart = 56_800_235_584L;   // 62^6, so every key is 7 characters long

    public KeyRangeAllocator(long rangeSize) {
        this.rangeSize = rangeSize;
    }

    // synchronized: two servers asking at once must get DIFFERENT ranges
    public synchronized KeyRange allocateRange() {
        KeyRange range = new KeyRange(nextStart, nextStart + rangeSize - 1);
        nextStart = nextStart + rangeSize;
        return range;
    }
}

package Entities;

public class KeyRange {
    private final long start;
    private final long end;
    private long current;                   // next number to give out

    public KeyRange(long start, long end) {
        this.start = start;
        this.end = end;
        this.current = start;
    }

    public boolean hasNext() { return current <= end; }

    public long next() {
        return current++;                   // give the number, then move forward
    }

    public long getStart() { return start; }
    public long getEnd() { return end; }
}

package Services;

public class KeyGeneratorFactory {
    public static KeyGenerator create(String type, KeyRangeAllocator allocator) {
        if (type.equals("RANGE")) {
            return new RangeKeyGenerator(allocator);
        } else if (type.equals("RANDOM")) {
            return new RandomGenerator();
        }
        throw new IllegalArgumentException("Unknown key generator type: " + type);
    }
}

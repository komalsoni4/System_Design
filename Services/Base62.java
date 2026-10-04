package Services;
public class Base62 {
    private static final String CHARS =
        "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";

    // number -> text, e.g. 125 -> "21"   (125 = 2*62 + 1)
    public static String encode(long number) {
        if (number == 0) return "0";
        String result = "";
        while (number > 0) {
            result = CHARS.charAt((int) (number % 62)) + result;
            number = number / 62;
        }
        return result;
    }
}

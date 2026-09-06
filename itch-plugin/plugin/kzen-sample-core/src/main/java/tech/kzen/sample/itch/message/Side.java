package tech.kzen.sample.itch.message;


public enum Side {
    BUY('B'),
    SELL('S');

    private final char code;

    Side(char code) {
        this.code = code;
    }

    public char code() {
        return code;
    }

    public static Side ofCode(char code) {
        return switch (code) {
            case 'B' -> BUY;
            case 'S' -> SELL;
            default -> throw new IllegalArgumentException("Unknown side code: '" + code + "'");
        };
    }
}

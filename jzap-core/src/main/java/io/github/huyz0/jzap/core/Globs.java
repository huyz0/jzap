package io.github.huyz0.jzap.core;

import java.util.regex.Pattern;

/**
 * Class-name globs, using the convention PIT users already know: {@code *} matches any run
 * of characters including dots, so {@code com.example.*} covers subpackages.
 */
public final class Globs {

    private Globs() {
    }

    public static boolean matches(String glob, String value) {
        return toPattern(glob).matcher(value).matches();
    }

    static Pattern toPattern(String glob) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            switch (c) {
                case '*' -> sb.append(".*");
                case '?' -> sb.append('.');
                case '.', '$', '(', ')', '[', ']', '{', '}', '+', '^', '|', '\\' -> sb.append('\\').append(c);
                default -> sb.append(c);
            }
        }
        return Pattern.compile(sb.toString());
    }
}

package sample;

/** No test touches this class, so every mutant in it must come back as NO_COVERAGE. */
public class Strings {

    public String shout(String text) {
        return text.trim();
    }

    public int length(String text) {
        return text.length() + 1;
    }
}

package why.util;

import java.util.Collection;

/** Small spelling hints for "did you mean" error messages. */
public final class Suggest {
    private Suggest() {}

    /** Returns {@code " (did you mean 'name'?)"}, or an empty string when nothing is close. */
    public static String didYouMean(String name, Collection<String> candidates) {
        String best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (String candidate : candidates) {
            if (candidate.equals(name)) return "";
            int distance = distance(name, candidate);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = candidate;
            }
        }
        if (best == null || bestDistance > Math.max(2, name.length() / 3)) return "";
        return " (did you mean '" + best + "'?)";
    }

    private static int distance(String a, String b) {
        int[] previous = new int[b.length() + 1];
        int[] current = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) previous[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1), previous[j - 1] + cost);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[b.length()];
    }
}

package io.github.narinehzargarian.recon.internal;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** How alike two descriptions are, from 0 to 1. Banks and ledgers word the same payment differently. */
public final class Similarity {

    private Similarity() {
    }

    public static String normalize(String text) {
        return text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9 ]", " ").replaceAll("\\s+", " ").trim();
    }

    /** A description prepared once, so comparing it many times stays fast. */
    public record Profile(boolean empty, Set<String> words, Set<String> trigrams) {

        public static Profile of(String text) {
            String normalized = normalize(text);
            return new Profile(normalized.isEmpty(), Similarity.words(normalized), Similarity.trigrams(normalized));
        }

        public double similarity(Profile other) {
            if (empty || other.empty) {
                return 0;
            }
            return Math.max(jaccard(words, other.words), jaccard(trigrams, other.trigrams));
        }
    }

    /**
     * The better of word overlap and three-letter overlap. Words catch reordering; letter triples
     * catch truncation, like "AMAZON MKTP" against "Amazon Marketplace".
     */
    public static double score(String a, String b) {
        return Profile.of(a).similarity(Profile.of(b));
    }

    private static Set<String> words(String text) {
        Set<String> result = new HashSet<>();
        for (String word : text.split(" ")) {
            // Long numbers are usually transaction codes that differ between sources.
            if (!word.matches("\\d{4,}")) {
                result.add(word);
            }
        }
        return result;
    }

    private static Set<String> trigrams(String text) {
        Set<String> result = new HashSet<>();
        String padded = " " + text.replaceAll("\\d{4,}", "") + " ";
        for (int i = 0; i + 3 <= padded.length(); i++) {
            result.add(padded.substring(i, i + 3));
        }
        return result;
    }

    private static double jaccard(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) {
            return 0;
        }
        int shared = 0;
        for (String item : a) {
            if (b.contains(item)) {
                shared++;
            }
        }
        return (double) shared / (a.size() + b.size() - shared);
    }
}

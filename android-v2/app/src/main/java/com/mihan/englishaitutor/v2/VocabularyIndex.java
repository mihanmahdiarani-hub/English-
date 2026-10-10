package com.mihan.englishaitutor.v2;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Actual transcript-backed film lexicon, independent from Gemini availability.
 *
 * EVERY spoken orthographic word from the selected dialogue / whole movie
 * appears in WORDS, even stop words such as a, the, is, to, or I.
 * Specialized categories come only from Gemini-suggested phrases verified
 * against a contiguous word sequence actually present in THAT dialogue.
 * Guessed or unrelated phrases are NEVER added as if they were heard.
 */
final class VocabularyIndex {
    private static final Pattern WORD = Pattern.compile("[A-Za-z]+(?:['\u2019][A-Za-z]+)*");

    enum Category {
        WORD("همه واژه‌ها", "Words"),
        IDIOM("اصطلاح‌های کنایی", "Idioms"),
        COLLOCATION("هم‌آیی‌ها", "Collocations"),
        PHRASAL_VERB("افعال عبارتی", "Phrasal verbs"),
        FIXED_EXPRESSION("عبارت‌های ثابت", "Fixed expressions"),
        SLANG("عامیانه و محاوره", "Slang"),
        DISCOURSE_MARKER("نشانگرهای گفتگو", "Discourse markers"),
        CONTRACTION("شکل‌های کوتاه‌شده", "Contractions"),
        COMPOUND("ترکیب‌واژه‌ها", "Compounds"),
        PROVERB("ضرب‌المثل‌ها", "Proverbs"),
        OTHER("عبارت‌های دیگر", "Other phrases");

        final String persian;
        final String english;
        Category(String persian, String english) {
            this.persian = persian; this.english = english;
        }
        String title() { return persian + " · " + english; }

        static Category parse(String raw) {
            if (raw == null) return null;
            String normalized = raw.toUpperCase(Locale.US)
                    .trim().replace('-', '_').replace(' ', '_');
            if (normalized.equals("WORDS") || normalized.equals("VOCABULARY"))
                normalized = "WORD";
            if (normalized.equals("IDIOMS")) normalized = "IDIOM";
            if (normalized.equals("COLLOCATIONS")) normalized = "COLLOCATION";
            if (normalized.equals("PHRASAL_VERBS")) normalized = "PHRASAL_VERB";
            if (normalized.equals("SLANGS")) normalized = "SLANG";
            try { return Category.valueOf(normalized); }
            catch (IllegalArgumentException ignored) { return null; }
        }
    }

    static final class Phrase {
        final String term;
        final Category category;
        final String meaningFa;
        final String note;

        Phrase(String term, Category category, String meaningFa, String note) {
            this.term = normalizeSpaces(term);
            this.category = category;
            this.meaningFa = normalizeSpaces(meaningFa);
            this.note = normalizeSpaces(note);
        }
    }

    static final class Entry {
        final String term;
        final Category category;
        String meaningFa;
        String note;
        int firstDialogue;
        int occurrences;

        Entry(String term, Category category, String meaningFa, String note, int line) {
            this.term = term;
            this.category = category;
            this.meaningFa = meaningFa;
            this.note = note;
            this.firstDialogue = line;
            this.occurrences = 1;
        }
    }

    private VocabularyIndex() {}

    static List<String> words(String line) {
        List<String> out = new ArrayList<>();
        if (line == null) return out;
        Matcher matcher = WORD.matcher(line);
        while (matcher.find()) {
            out.add(matcher.group().replace('\u2019', '\'').toLowerCase(Locale.US));
        }
        return out;
    }

    static boolean heardInDialogue(String dialogue, String term) {
        List<String> source = words(dialogue);
        List<String> phrase = words(term);
        if (phrase.isEmpty() || phrase.size() > source.size()) return false;
        for (int i = 0; i <= source.size() - phrase.size(); i++) {
            boolean same = true;
            for (int j = 0; j < phrase.size(); j++) {
                if (!source.get(i + j).equals(phrase.get(j))) {
                    same = false; break;
                }
            }
            if (same) return true;
        }
        return false;
    }

    private static String normalizeSpaces(String source) {
        if (source == null) return "";
        return source.replaceAll("\\s+", " ").trim();
    }

    /**
     * @param lines all saved movie dialogue texts (zero-based ordering).
     * @param enrichments JSON-derived Gemini lexical classifications, keyed
     *                    by the SOURCE dialogue index, never neighboring rows.
     * @param selectedDialogue -1 means full movie; otherwise exactly one row.
     */
    static List<Entry> build(List<String> lines,
                             Map<Integer, List<Phrase>> enrichments,
                             int selectedDialogue) {
        if (lines == null || lines.isEmpty()) return Collections.emptyList();
        if (selectedDialogue < -1 || selectedDialogue >= lines.size())
            return Collections.emptyList();

        LinkedHashMap<String, Entry> unique = new LinkedHashMap<>();
        int start = selectedDialogue < 0 ? 0 : selectedDialogue;
        int end = selectedDialogue < 0 ? lines.size() : selectedDialogue + 1;

        // Every WORD is known directly from Whisper's extracted text; a
        // partial Gemini reply cannot silently omit "the", "to", etc.
        for (int i = start; i < end; i++) {
            for (String word : words(lines.get(i))) {
                String key = "WORD|" + word;
                Entry existing = unique.get(key);
                if (existing != null) existing.occurrences++;
                else unique.put(key, new Entry(word, Category.WORD, "", "", i));
            }
        }

        if (enrichments == null) return new ArrayList<>(unique.values());
        for (int i = start; i < end; i++) {
            List<Phrase> suggestions = enrichments.get(i);
            if (suggestions == null) continue;
            String line = lines.get(i);
            for (Phrase suggestion : suggestions) {
                if (suggestion == null || suggestion.category == null
                        || suggestion.term.isEmpty()
                        || !heardInDialogue(line, suggestion.term)) continue;
                String normalized = String.join(" ", words(suggestion.term));
                if (normalized.isEmpty()) continue;
                String key = suggestion.category.name() + "|" + normalized;
                Entry existing = unique.get(key);
                if (existing == null) {
                    existing = new Entry(suggestion.term, suggestion.category,
                            suggestion.meaningFa, suggestion.note, i);
                    unique.put(key, existing);
                } else {
                    // Count only once for a classified expression in this
                    // dialogue, rather than double-count Gemini duplicates.
                    if (existing.firstDialogue != i) existing.occurrences++;
                    if (existing.meaningFa.isEmpty()) existing.meaningFa = suggestion.meaningFa;
                    if (existing.note.isEmpty()) existing.note = suggestion.note;
                }
                // WORD classification enriches the always-complete raw word
                // list without creating a second disconnected word row.
                if (suggestion.category == Category.WORD) {
                    Entry word = unique.get("WORD|" + normalized);
                    if (word != null) {
                        if (word.meaningFa.isEmpty()) word.meaningFa = suggestion.meaningFa;
                        if (word.note.isEmpty()) word.note = suggestion.note;
                    }
                }
            }
        }
        return new ArrayList<>(unique.values());
    }

    static Map<Category, Integer> counts(List<Entry> entries) {
        Map<Category, Integer> counts = new EnumMap<>(Category.class);
        for (Category category : Category.values()) counts.put(category, 0);
        if (entries != null) {
            for (Entry entry : entries)
                counts.put(entry.category, counts.get(entry.category) + 1);
        }
        return counts;
    }

    static List<Entry> filter(List<Entry> entries, Category category, String search) {
        List<Entry> out = new ArrayList<>();
        String query = normalizeSpaces(search).toLowerCase(Locale.US);
        if (entries == null) return out;
        for (Entry entry : entries) {
            if (category != null && entry.category != category) continue;
            if (!query.isEmpty()
                    && !entry.term.toLowerCase(Locale.US).contains(query)
                    && !entry.meaningFa.toLowerCase(Locale.US).contains(query)) continue;
            out.add(entry);
        }
        return out;
    }
}

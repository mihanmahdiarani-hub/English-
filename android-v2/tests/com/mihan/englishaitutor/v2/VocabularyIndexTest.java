package com.mihan.englishaitutor.v2;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Real transcript-backed lexicon coverage, categories and anti-hallucination. */
public final class VocabularyIndexTest {
    private static int checks;
    private static void ok(boolean value, String reason) {
        checks++;
        if (!value) throw new AssertionError(reason);
    }
    private static VocabularyIndex.Entry lookup(List<VocabularyIndex.Entry> words,
                                                VocabularyIndex.Category category,
                                                String value) {
        for (VocabularyIndex.Entry entry : words) {
            if (entry.category == category && entry.term.equalsIgnoreCase(value))
                return entry;
        }
        return null;
    }

    public static void main(String[] args) {
        List<String> movie = Arrays.asList(
                "I don't know what you mean. Take off your shoes.",
                "I don't know. Give up? No way!",
                "It’s all right. Take off your coat.");
        Map<Integer, List<VocabularyIndex.Phrase>> classification = new HashMap<>();
        classification.put(0, Arrays.asList(
                new VocabularyIndex.Phrase("I", VocabularyIndex.Category.WORD, "من", ""),
                new VocabularyIndex.Phrase("don't", VocabularyIndex.Category.WORD, "نمی‌", "do not"),
                new VocabularyIndex.Phrase("take off", VocabularyIndex.Category.PHRASAL_VERB,
                        "درآوردن", "Take off your shoes"),
                new VocabularyIndex.Phrase("take off", VocabularyIndex.Category.PHRASAL_VERB,
                        "درآوردن", "duplicate AI response"),
                new VocabularyIndex.Phrase("kick the bucket", VocabularyIndex.Category.IDIOM,
                        "مردن", "hallucinated; never spoken"),
                new VocabularyIndex.Phrase("take shoes", VocabularyIndex.Category.COLLOCATION,
                        "ترکیب نادرست", "not contiguous")));
        classification.put(1, Arrays.asList(
                new VocabularyIndex.Phrase("give up", VocabularyIndex.Category.PHRASAL_VERB,
                        "تسلیم‌شدن", ""),
                new VocabularyIndex.Phrase("no way", VocabularyIndex.Category.IDIOM,
                        "به هیچ وجه", ""),
                new VocabularyIndex.Phrase("give up", VocabularyIndex.Category.PHRASAL_VERB,
                        "تسلیم‌شدن", "duplicate")));
        classification.put(2, Arrays.asList(
                new VocabularyIndex.Phrase("take off", VocabularyIndex.Category.PHRASAL_VERB,
                        "درآوردن", "coat context"),
                new VocabularyIndex.Phrase("all right", VocabularyIndex.Category.FIXED_EXPRESSION,
                        "باشه", "")));

        List<VocabularyIndex.Entry> all = VocabularyIndex.build(movie, classification, -1);
        VocabularyIndex.Entry i = lookup(all, VocabularyIndex.Category.WORD, "i");
        VocabularyIndex.Entry dont = lookup(all, VocabularyIndex.Category.WORD, "don't");
        VocabularyIndex.Entry take = lookup(all, VocabularyIndex.Category.WORD, "take");
        VocabularyIndex.Entry the = lookup(all, VocabularyIndex.Category.WORD, "your");
        ok(i != null && i.occurrences == 2, "include pronouns, exact count, not AI duplicates");
        ok(dont != null && dont.occurrences == 2 && !dont.meaningFa.isEmpty(),
                "contractions counted from transcript and enriched with meaning");
        ok(take != null && take.occurrences == 2, "all spoken repeated verbs appear");
        ok(the != null && the.occurrences == 2, "all basic functional words included");
        ok(lookup(all, VocabularyIndex.Category.WORD, "what") != null,
                "every word including question words appears");
        ok(lookup(all, VocabularyIndex.Category.WORD, "it's") != null,
                "curly apostrophe normalized");
        ok(lookup(all, VocabularyIndex.Category.IDIOM, "kick the bucket") == null,
                "a Gemini hallucination is never displayed as heard");
        ok(lookup(all, VocabularyIndex.Category.COLLOCATION, "take shoes") == null,
                "phrases must match CONTIGUOUS spoken words");
        VocabularyIndex.Entry takeOff = lookup(all, VocabularyIndex.Category.PHRASAL_VERB, "take off");
        ok(takeOff != null && takeOff.occurrences == 2,
                "one classified phrasal verb across two distinct dialogues");
        VocabularyIndex.Entry giveUp = lookup(all, VocabularyIndex.Category.PHRASAL_VERB, "give up");
        ok(giveUp != null && giveUp.occurrences == 1,
                "duplicate Gemini suggestion in one line does not inflate count");
        ok(lookup(all, VocabularyIndex.Category.IDIOM, "no way") != null,
                "genuine idiom is categorized");
        ok(lookup(all, VocabularyIndex.Category.FIXED_EXPRESSION, "all right") != null,
                "genuine fixed expression is categorized");

        List<VocabularyIndex.Entry> selected =
                VocabularyIndex.build(movie, classification, 0);
        ok(lookup(selected, VocabularyIndex.Category.WORD, "shoes") != null,
                "current scope includes all its own words");
        ok(lookup(selected, VocabularyIndex.Category.WORD, "coat") == null,
                "current scope does not borrow any words from next dialogue");
        ok(lookup(selected, VocabularyIndex.Category.IDIOM, "no way") == null,
                "current scope does not borrow specialized idioms from next line");
        ok(lookup(selected, VocabularyIndex.Category.PHRASAL_VERB, "take off") != null,
                "current phrase remains visible in current scope");
        ok(VocabularyIndex.counts(all).get(VocabularyIndex.Category.PROVERB) == 0,
                "unheard/unknown category remains truly empty");
        ok(VocabularyIndex.Category.parse("PHRASAL_VERB")
                == VocabularyIndex.Category.PHRASAL_VERB, "standard category decoded");
        ok(VocabularyIndex.Category.parse("invented-fictional-type") == null,
                "unknown AI labels cannot fabricate categories");

        List<VocabularyIndex.Entry> searched =
                VocabularyIndex.filter(all, VocabularyIndex.Category.WORD, "نمی");
        ok(searched.size() == 1 && searched.get(0).term.equals("don't"),
                "Persian meaning search locates exact word");
        List<VocabularyIndex.Entry> idioms =
                VocabularyIndex.filter(all, VocabularyIndex.Category.IDIOM, "");
        ok(idioms.size() == 1, "only one verified idiom in film");
        List<VocabularyIndex.Entry> rawOnly = VocabularyIndex.build(movie, null, -1);
        ok(lookup(rawOnly, VocabularyIndex.Category.WORD, "coat") != null,
                "every word appears WITHOUT Gemini, offline");
        ok(VocabularyIndex.counts(rawOnly).get(VocabularyIndex.Category.IDIOM) == 0,
                "never fake idioms when Gemini not yet analyzed");
        ok(VocabularyIndex.heardInDialogue(
                "Don't worry, take it easy.", "take it easy"),
                "whole multiword phrase boundary matched");
        ok(!VocabularyIndex.heardInDialogue(
                "Take the keys, then leave.", "take leave"),
                "noncontiguous word sequences rejected");
        System.out.println("PASS " + checks
                + " complete vocabulary / categorized expressions / anti-hallucination checks");
    }
}

package com.drc.whatsappbot.service;

import com.drc.whatsappbot.dto.CatalogMatchResult;
import com.drc.whatsappbot.dto.ParsedItem;
import com.drc.whatsappbot.model.CatalogProduct;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class FuzzyCatalogMatcherService {

    private static final Pattern PACK_WEIGHT_PATTERN = Pattern.compile(
            "(\\d+(?:\\.\\d+)?)\\s*(kg|g|gm|l|ltr|ml)\\b",
            Pattern.CASE_INSENSITIVE
    );

    private static final Map<String, String> HINGLISH_QUERY_SYNONYMS = Map.ofEntries(
            Map.entry("doodh", "milk"),
            Map.entry("dudh", "milk"),
            Map.entry("chawal", "rice basmati"),
            Map.entry("bhat", "rice"),
            Map.entry("aata", "atta"),
            Map.entry("gehun", "atta"),
            Map.entry("aashirvad", "aashirvaad"),
            Map.entry("ashirwad", "aashirvaad"),
            Map.entry("ashirvaad", "aashirvaad"),
            Map.entry("daal", "dal"),
            Map.entry("arhar", "toor dal"),
            Map.entry("tuar", "toor dal"),
            Map.entry("mung", "moong"),
            Map.entry("tel", "oil sunflower"),
            Map.entry("namak", "salt"),
            Map.entry("chai", "tea"),
            Map.entry("patti", "tea"),
            Map.entry("makkhan", "butter"),
            Map.entry("maska", "butter"),
            Map.entry("sabun", "soap dettol"),
            Map.entry("biskut", "biscuits parle"),
            Map.entry("parleg", "parle-g"),
            Map.entry("bhujiya", "bhujia"),
            Map.entry("namkeen", "bhujia"),
            Map.entry("dahi", "curd"),
            Map.entry("cheeni", "sugar"),
            Map.entry("chini", "sugar"),
            Map.entry("manjan", "toothpaste colgate"),
            Map.entry("toothpaste", "colgate closeup"),
            Map.entry("maggie", "maggi"),
            Map.entry("cornflakes", "corn flakes")
    );

    public record ResolvedMatch(CatalogMatchResult result, int effectiveQty) {}

    public ResolvedMatch match(ParsedItem parsedItem, List<CatalogProduct> catalog) {
        if (catalog == null || catalog.isEmpty()) {
            return new ResolvedMatch(new CatalogMatchResult(
                    parsedItem.rawText(), parsedItem.quantity(), false, false,
                    null, null, null, null, 0, 0.0, "Catalog is empty", List.of()
            ), 0);
        }

        String expandedQuery = expandSynonyms(parsedItem.productQuery());
        Set<String> queryTokens = tokenize(expandedQuery);

        List<ScoredProduct> scored = new ArrayList<>();
        for (CatalogProduct product : catalog) {
            ScoreOutcome outcome = scoreProduct(expandedQuery, queryTokens, parsedItem.packSizeMention(), product);
            scored.add(new ScoredProduct(product, outcome.score(), outcome.reason()));
        }

        scored.sort(Comparator.comparingDouble(ScoredProduct::score).reversed());
        ScoredProduct best = scored.get(0);

        List<String> alternatives = scored.stream()
                .filter(s -> !Objects.equals(s.product().getId(), best.product().getId()))
                .filter(s -> s.product().getStock() > 0 && s.score() >= 0.25)
                .limit(3)
                .map(s -> s.product().getName() + " (₹" + s.product().getPrice() + ")")
                .toList();

        if (best.score() < 0.36) {
            return new ResolvedMatch(new CatalogMatchResult(
                    parsedItem.rawText(),
                    parsedItem.quantity(),
                    false,
                    false,
                    null,
                    null,
                    null,
                    null,
                    0,
                    round2(best.score()),
                    "No strong match (best score " + round2(best.score()) + ")",
                    alternatives
            ), 0);
        }

        CatalogProduct matched = best.product();
        int effectiveQty = computeEffectiveQuantity(parsedItem, matched);
        boolean outOfStock = matched.getStock() <= 0;

        if (outOfStock) {
            List<String> categoryAlts = catalog.stream()
                    .filter(p -> !Objects.equals(p.getId(), matched.getId()) && p.getStock() > 0)
                    .filter(p -> Objects.equals(p.getCategoryName(), matched.getCategoryName()))
                    .limit(3)
                    .map(p -> p.getName() + " (₹" + p.getPrice() + ")")
                    .toList();
            return new ResolvedMatch(new CatalogMatchResult(
                    parsedItem.rawText(),
                    effectiveQty,
                    true,
                    true,
                    matched.getId(),
                    matched.getName(),
                    matched.getPrice(),
                    matched.getUnit(),
                    matched.getStock(),
                    round2(best.score()),
                    best.reason() + " [OUT OF STOCK]",
                    categoryAlts
            ), 0);
        }

        int cappedQty = Math.min(effectiveQty, matched.getStock());
        return new ResolvedMatch(new CatalogMatchResult(
                parsedItem.rawText(),
                cappedQty,
                true,
                false,
                matched.getId(),
                matched.getName(),
                matched.getPrice(),
                matched.getUnit(),
                matched.getStock(),
                round2(best.score()),
                best.reason(),
                alternatives
        ), cappedQty);
    }

    private ScoreOutcome scoreProduct(String expandedQuery, Set<String> queryTokens, String packMention, CatalogProduct product) {
        String nameLower = product.getName().toLowerCase(Locale.ROOT);
        String synLower = product.getSynonyms() == null ? "" : product.getSynonyms().toLowerCase(Locale.ROOT);
        String combinedText = nameLower + " " + synLower.replace(",", " ");
        Set<String> productTokens = tokenize(combinedText);
        Set<String> nameTokens = tokenize(nameLower);

        // Exact substring bonus
        String normalizedQuery = normalizeText(expandedQuery);
        String normalizedName = normalizeText(nameLower);
        if (normalizedName.contains(normalizedQuery) && normalizedQuery.length() >= 3) {
            return new ScoreOutcome(0.96, "Exact name phrase match");
        }

        // Token overlap + fuzzy token match
        double matchedTokenScore = 0.0;
        StringBuilder reasonBuilder = new StringBuilder();
        for (String qt : queryTokens) {
            if (isUnitWord(qt)) continue;
            double bestTok = 0.0;
            for (String pt : productTokens) {
                if (pt.equals(qt)) {
                    bestTok = 1.0;
                    break;
                }
                if (pt.contains(qt) || qt.contains(pt)) {
                    bestTok = Math.max(bestTok, 0.85);
                } else {
                    double sim = levenshteinSimilarity(qt, pt);
                    if (sim >= 0.72) {
                        bestTok = Math.max(bestTok, sim * 0.9);
                    }
                }
            }
            if (bestTok > 0 && nameTokens.contains(qt)) {
                bestTok = Math.min(1.0, bestTok + 0.08);
            }
            matchedTokenScore += bestTok;
        }

        long meaningfulQueryTokens = queryTokens.stream().filter(t -> !isUnitWord(t)).count();
        if (meaningfulQueryTokens == 0) meaningfulQueryTokens = 1;

        double coverage = matchedTokenScore / meaningfulQueryTokens;

        // Synonym hit bonus
        boolean synHit = Arrays.stream(synLower.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .anyMatch(syn -> expandedQuery.contains(syn) || syn.equals(normalizedQuery));

        double finalScore = coverage * 0.85 + (synHit ? 0.15 : 0.0);

        // Pack-size alignment bonus (e.g., 5kg matches "5 kg")
        if (packMention != null && !packMention.isBlank()) {
            Double reqMlOrG = toBaseMilliOrGrams(packMention);
            Double prodMlOrG = toBaseMilliOrGrams(nameLower);
            if (reqMlOrG != null && prodMlOrG != null && Math.abs(reqMlOrG - prodMlOrG) < 0.01) {
                finalScore = Math.min(0.99, finalScore + 0.08);
                reasonBuilder.append("Pack-size matched (").append(packMention).append(")");
            }
        }

        if (synHit) {
            if (!reasonBuilder.isEmpty()) reasonBuilder.append(" + ");
            reasonBuilder.append("Hinglish synonym match");
        } else if (finalScore >= 0.7) {
            if (!reasonBuilder.isEmpty()) reasonBuilder.append(" + ");
            reasonBuilder.append("Strong fuzzy token match");
        } else {
            if (!reasonBuilder.isEmpty()) reasonBuilder.append(" + ");
            reasonBuilder.append("Fuzzy similarity");
        }

        return new ScoreOutcome(Math.min(0.99, finalScore), reasonBuilder.toString());
    }

    int computeEffectiveQuantity(ParsedItem item, CatalogProduct product) {
        int baseQty = Math.max(1, item.quantity());
        Double productPackBase = toBaseMilliOrGrams(product.getName());
        if (productPackBase == null || productPackBase <= 0) {
            return baseQty;
        }

        // If packSizeMention explicitly specified (e.g. "5kg", "10kg", "500g")
        if (item.packSizeMention() != null && !item.packSizeMention().isBlank()) {
            Double reqBase = toBaseMilliOrGrams(item.packSizeMention());
            if (reqBase != null && reqBase > 0) {
                int packsPerUnit = (int) Math.max(1, Math.round(reqBase / productPackBase));
                return baseQty * packsPerUnit;
            }
        }

        // If LLM returned weight/volume directly as quantity (e.g. qty=5, unit="kg")
        if (isWeightOrVolume(item.unit()) && baseQty > 1) {
            Double reqBase = toBaseMilliOrGrams(baseQty + item.unit());
            if (reqBase != null && reqBase > 0) {
                return (int) Math.max(1, Math.round(reqBase / productPackBase));
            }
        }

        return baseQty;
    }

    private Double toBaseMilliOrGrams(String text) {
        if (text == null) return null;
        Matcher m = PACK_WEIGHT_PATTERN.matcher(text);
        if (!m.find()) return null;
        double val = Double.parseDouble(m.group(1));
        String unit = m.group(2).toLowerCase(Locale.ROOT);
        return switch (unit) {
            case "kg", "l", "ltr" -> val * 1000.0;
            case "g", "gm", "ml" -> val;
            default -> null;
        };
    }

    private String expandSynonyms(String query) {
        String lower = normalizeText(query);
        String[] tokens = lower.split("\\s+");
        StringBuilder sb = new StringBuilder(lower);
        for (String tok : tokens) {
            String mapped = HINGLISH_QUERY_SYNONYMS.get(tok);
            if (mapped != null) {
                sb.append(" ").append(mapped);
            }
        }
        return sb.toString();
    }

    private Set<String> tokenize(String text) {
        Set<String> set = new LinkedHashSet<>();
        for (String tok : normalizeText(text).split("\\s+")) {
            if (!tok.isBlank()) set.add(tok);
        }
        return set;
    }

    private String normalizeText(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT)
                .replaceAll("['’\\-]", " ")
                .replaceAll("[^a-z0-9\\s]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private boolean isUnitWord(String tok) {
        return Set.of("kg", "g", "gm", "l", "ltr", "ml", "pack", "packet", "bottle", "box", "bag", "pcs").contains(tok);
    }

    private boolean isWeightOrVolume(String unit) {
        return unit != null && Set.of("kg", "g", "l", "ml").contains(unit.toLowerCase(Locale.ROOT));
    }

    static double levenshteinSimilarity(String a, String b) {
        if (a.equals(b)) return 1.0;
        int maxLen = Math.max(a.length(), b.length());
        if (maxLen == 0) return 1.0;
        int dist = levenshteinDistance(a, b);
        return 1.0 - ((double) dist / maxLen);
    }

    private static int levenshteinDistance(String a, String b) {
        int[] prev = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            int[] curr = new int[b.length() + 1];
            curr[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                curr[j] = Math.min(Math.min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            prev = curr;
        }
        return prev[b.length()];
    }

    private double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private record ScoredProduct(CatalogProduct product, double score, String reason) {}
    private record ScoreOutcome(double score, String reason) {}
}


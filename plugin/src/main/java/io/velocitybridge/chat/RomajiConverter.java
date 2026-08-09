package io.velocitybridge.chat;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ローマ字（英字）入力を日本語（ひらがな）へ自動変換する。
 *
 * <p>LunaChat のローマ字→かな変換に相当する機能。最長一致でかなに変換し、
 * 促音（っ）・撥音（ん）・長音（おう/えい 等）・拗音（きゃ 等）に対応する。</p>
 */
public final class RomajiConverter {

    private static final Map<String, String> KANA = new LinkedHashMap<>();

    static {
        put("kka", "っか");
        put("kki", "っき");
        put("kku", "っく");
        put("kke", "っけ");
        put("kko", "っこ");
        put("ssa", "っさ");
        put("sshi", "っし");
        put("ssu", "っす");
        put("sse", "っせ");
        put("sso", "っそ");
        put("tta", "った");
        put("tchi", "っち");
        put("ttsu", "っつ");
        put("tte", "って");
        put("tto", "っと");
        put("ppa", "っぱ");
        put("ppi", "っぴ");
        put("ppu", "っぷ");
        put("ppe", "っぺ");
        put("ppo", "っぽ");
        put("tcha", "っちゃ");
        put("tchu", "っちゅ");
        put("tcho", "っちょ");

        put("kya", "きゃ");
        put("kyu", "きゅ");
        put("kyo", "きょ");
        put("gya", "ぎゃ");
        put("gyu", "ぎゅ");
        put("gyo", "ぎょ");
        put("sha", "しゃ");
        put("shu", "しゅ");
        put("sho", "しょ");
        put("sha", "しゃ");
        put("ja", "じゃ");
        put("ju", "じゅ");
        put("jo", "じょ");
        put("cha", "ちゃ");
        put("chu", "ちゅ");
        put("cho", "ちょ");
        put("nya", "にゃ");
        put("nyu", "にゅ");
        put("nyo", "にょ");
        put("hya", "ひゃ");
        put("hyu", "ひゅ");
        put("hyo", "ひょ");
        put("bya", "びゃ");
        put("byu", "びゅ");
        put("byo", "びょ");
        put("pya", "ぴゃ");
        put("pyu", "ぴゅ");
        put("pyo", "ぴょ");
        put("mya", "みゃ");
        put("myu", "みゅ");
        put("myo", "みょ");
        put("rya", "りゃ");
        put("ryu", "りゅ");
        put("ryo", "りょ");

        put("shi", "し");
        put("chi", "ち");
        put("tsu", "つ");
        put("fu", "ふ");
        put("ji", "じ");
        put("di", "ぢ");
        put("du", "づ");

        put("ka", "か");
        put("ki", "き");
        put("ku", "く");
        put("ke", "け");
        put("ko", "こ");
        put("ga", "が");
        put("gi", "ぎ");
        put("gu", "ぐ");
        put("ge", "げ");
        put("go", "ご");
        put("sa", "さ");
        put("si", "し");
        put("su", "す");
        put("se", "せ");
        put("so", "そ");
        put("za", "ざ");
        put("zi", "じ");
        put("zu", "ず");
        put("ze", "ぜ");
        put("zo", "ぞ");
        put("ta", "た");
        put("ti", "ち");
        put("tu", "つ");
        put("te", "て");
        put("to", "と");
        put("da", "だ");
        put("de", "で");
        put("do", "ど");
        put("na", "な");
        put("ni", "に");
        put("nu", "ぬ");
        put("ne", "ね");
        put("no", "の");
        put("ha", "は");
        put("hi", "ひ");
        put("hu", "ふ");
        put("he", "へ");
        put("ho", "ほ");
        put("ba", "ば");
        put("bi", "び");
        put("bu", "ぶ");
        put("be", "べ");
        put("bo", "ぼ");
        put("pa", "ぱ");
        put("pi", "ぴ");
        put("pu", "ぷ");
        put("pe", "ぺ");
        put("po", "ぽ");
        put("ma", "ま");
        put("mi", "み");
        put("mu", "む");
        put("me", "め");
        put("mo", "も");
        put("ya", "や");
        put("yu", "ゆ");
        put("yo", "よ");
        put("ra", "ら");
        put("ri", "り");
        put("ru", "る");
        put("re", "れ");
        put("ro", "ろ");
        put("wa", "わ");
        put("wo", "を");
        put("a", "あ");
        put("i", "い");
        put("u", "う");
        put("e", "え");
        put("o", "お");

        put("aa", "ああ");
        put("ii", "いい");
        put("uu", "うう");
        put("ee", "ええ");
        put("oo", "おお");
        put("ou", "おう");
        put("ei", "えい");
    }

    private static void put(String romaji, String kana) {
        KANA.put(romaji, kana);
    }

    private RomajiConverter() {
    }

    /**
     * ローマ字入力をひらがなへ変換する。
     *
     * @param input ローマ字入力（英字）
     * @return 変換後のひらがな文字列
     */
    public static String convert(String input) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        String s = input.toLowerCase();
        int length = s.length();

        while (i < length) {
            char c = s.charAt(i);

            if (!isAsciiLetter(c)) {
                sb.append(c);
                i++;
                continue;
            }

            // 撥音「ん」：n の次が母音や y でなければ単独の「ん」として処理。
            // 「konnichiwa」のように nn が続く場合は先頭の n が「ん」になり、
            // 2 文字目は続く音節（na/ni/…）の先頭として再処理される。
            if (c == 'n') {
                if (i + 1 >= length || !isVowelOrY(s.charAt(i + 1))) {
                    sb.append('ん');
                    i++;
                    continue;
                }
            }

            // 促音：同じ子音が連続（kk, ss, tt, pp, cc 等）
            if (i + 1 < length && s.charAt(i) == s.charAt(i + 1) && isSokuonConsonant(c)) {
                sb.append('っ');
                i++;
                continue;
            }

            // 最長一致でかなへ変換
            boolean matched = false;
            for (int len = Math.min(3, length - i); len >= 1; len--) {
                String key = s.substring(i, i + len);
                String kana = KANA.get(key);
                if (kana != null) {
                    sb.append(kana);
                    i += len;
                    matched = true;
                    break;
                }
            }
            if (!matched) {
                sb.append(c);
                i++;
            }
        }
        return sb.toString();
    }

    private static final java.net.http.HttpClient HTTP_CLIENT = java.net.http.HttpClient.newBuilder()
            .connectTimeout(java.time.Duration.ofSeconds(3))
            .build();
    private static final com.google.gson.Gson GSON = new com.google.gson.Gson();

    /**
     * ローマ字入力をひらがなへ変換したのち、Google CGI API を使用して漢字に変換する。
     * ネットワークエラー等の場合はひらがな変換結果へフォールバックする。
     *
     * @param input ローマ字入力
     * @return 漢字かな交じり変換結果（失敗時はひらがな変換結果）
     */
    public static String convertToKanji(String input) {
        String kana = convert(input);
        if (kana.equals(input)) {
            return kana;
        }

        try {
            String encodedKana = java.net.URLEncoder.encode(kana, java.nio.charset.StandardCharsets.UTF_8);
            String url = "https://www.google.com/transliterate?langpair=ja-Hira|ja&text=" + encodedKana;

            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(url))
                    .timeout(java.time.Duration.ofSeconds(3))
                    .GET()
                    .build();

            java.net.http.HttpResponse<String> response = HTTP_CLIENT.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                com.google.gson.JsonArray root = GSON.fromJson(response.body(), com.google.gson.JsonArray.class);
                StringBuilder kanjiResult = new StringBuilder();
                for (com.google.gson.JsonElement element : root) {
                    com.google.gson.JsonArray phrase = element.getAsJsonObject().getAsJsonArray();
                    // phrase の構造: [0] = 元のひらがな文節, [1] = 変換候補の配列
                    if (phrase.size() >= 2 && phrase.get(1).isJsonArray()) {
                        com.google.gson.JsonArray candidates = phrase.get(1).getAsJsonArray();
                        if (candidates.size() > 0) {
                            kanjiResult.append(candidates.get(0).getAsString());
                            continue;
                        }
                    }
                    if (phrase.size() > 0) {
                        kanjiResult.append(phrase.get(0).getAsString());
                    }
                }
                String result = kanjiResult.toString();
                return result.isEmpty() ? kana : result;
            }
        } catch (Exception e) {
            // ネットワークエラーやタイムアウト時はひらがな変換結果にフォールバック
        }

        return kana;
    }

    private static boolean isAsciiLetter(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    private static boolean isVowelOrY(char c) {
        return c == 'a' || c == 'i' || c == 'u' || c == 'e' || c == 'o' || c == 'y';
    }

    private static boolean isSokuonConsonant(char c) {
        return c == 'k' || c == 's' || c == 't' || c == 'p' || c == 'c';
    }
}

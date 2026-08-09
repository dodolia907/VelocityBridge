package io.velocitybridge.chat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link RomajiConverter} のテスト。
 */
class RomajiConverterTest {

    @Test
    void convertsBasicKana() {
        assertEquals("こんにちわ", RomajiConverter.convert("konnichiwa"));
    }

    @Test
    void convertsSyllables() {
        assertEquals("かきくけこ", RomajiConverter.convert("kakikukeko"));
        assertEquals("さしすせそ", RomajiConverter.convert("sashisuseso"));
        assertEquals("たちつてと", RomajiConverter.convert("tachitsuteto"));
        assertEquals("なにぬねの", RomajiConverter.convert("naninuneno"));
    }

    @Test
    void convertsYoOn() {
        assertEquals("きゃきゅきょ", RomajiConverter.convert("kyakyukyo"));
        assertEquals("しゃしゅしょ", RomajiConverter.convert("shashusho"));
        assertEquals("ちゃちゅちょ", RomajiConverter.convert("chachucho"));
        assertEquals("にゃにゅにょ", RomajiConverter.convert("nyanyunyo"));
    }

    @Test
    void convertsSokuon() {
        assertEquals("いってらっしゃい", RomajiConverter.convert("itterasshai"));
        assertEquals("がっこう", RomajiConverter.convert("gakkou"));
    }

    @Test
    void convertsDakuonAndHandakuon() {
        assertEquals("がぎぐげご", RomajiConverter.convert("gagigugego"));
        assertEquals("ぱぴぷぺぽ", RomajiConverter.convert("papipupepo"));
        assertEquals("ばびぶべぼ", RomajiConverter.convert("babibubebo"));
    }

    @Test
    void convertsMixedRomajiAndNonAscii() {
        assertEquals("こんにちわ!ワールド", RomajiConverter.convert("konnichiwa!ワールド"));
    }

    @Test
    void preservesNonRomajiChars() {
        assertEquals("123 !?", RomajiConverter.convert("123 !?"));
        assertEquals("テスト", RomajiConverter.convert("テスト"));
        assertEquals("あ\nb", RomajiConverter.convert("a\nb"));
    }

    @Test
    void handlesLoneN() {
        assertEquals("たん", RomajiConverter.convert("tan"));
        assertEquals("ん", RomajiConverter.convert("n"));
    }

    @Test
    void convertsLongVowels() {
        assertEquals("おかあさん", RomajiConverter.convert("okaasan"));
        assertEquals("せんせい", RomajiConverter.convert("sensei"));
        assertEquals("そうじ", RomajiConverter.convert("souji"));
    }

    @Test
    void convertsUppercaseInput() {
        assertEquals("こんにちわ", RomajiConverter.convert("KONNICHIWA"));
    }

    @Test
    void convertToKanjiFallbackOrSuccess() {
        String result = RomajiConverter.convertToKanji("konnichiwa");
        // API通信が成功した場合は「こんにちは」等、失敗時はフォールバック「こんにちわ」
        org.junit.jupiter.api.Assertions.assertTrue(result.equals("こんにちは") || result.equals("こんにちわ"));
    }

    @Test
    void convertsSentenceToKanji() {
        String result = RomajiConverter.convertToKanji("Wagahai wa neko de aru. Namae wa mada nai.");
        org.junit.jupiter.api.Assertions.assertTrue(
                result.equals("吾輩は猫である。名前はまだない。") || result.contains("わがはい")
        );
    }

    @Test
    void japaneseInputBypassesConversion() {
        String japanese = "こんにちは！吾輩は猫である。";
        assertEquals(japanese, RomajiConverter.convertToKanji(japanese));
    }
}

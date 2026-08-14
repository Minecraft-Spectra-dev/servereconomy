package cn.choosec.economy.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for the gradient tag expansion (2+ colour stops). */
class MessageUtilTest {

    @Test
    void twoColourGradientMatchesLegacyBehaviour() {
        assertEquals("&#ff0000a&#0000ffb",
                MessageUtil.processGradientTags("<gradient:#ff0000:#0000ff>ab</gradient>"));
    }

    @Test
    void threeColourGradientUsesEachStopInOrder() {
        // 3 chars, 3 stops: each character is exactly one stop colour.
        assertEquals("&#ff0000a&#00ff00b&#0000ffc",
                MessageUtil.processGradientTags("<gradient:#ff0000:#00ff00:#0000ff>abc</gradient>"));
    }

    @Test
    void multiStopGradientReachesEndColour() {
        String out = MessageUtil.processGradientTags(
                "<gradient:#ff0000:#00ff00:#0000ff>abcd</gradient>");
        assertTrue(out.startsWith("&#ff0000a"), out);
        assertTrue(out.endsWith("&#0000ffd"), out);
    }

    @Test
    void singleColourGradientKeepsOneColour() {
        assertEquals("&#aabbccx&#aabbccy",
                MessageUtil.processGradientTags("<g:#aabbcc>xy</g>"));
    }

    @Test
    void gradientTagInsidePlainTextIsExpandedInPlace() {
        assertEquals("Hi &#ff0000a&#0000ffb!",
                MessageUtil.processGradientTags("Hi <g:#ff0000:#0000ff>ab</g>!"));
    }
}

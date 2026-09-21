package com.geneinvoice.email;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class EmailTextTest {

    private static final String NUL = String.valueOf((char) 0);
    private static final String HIGH = String.valueOf((char) 0xD83D);
    private static final String LOW = String.valueOf((char) 0xDE00);
    private static final String REPLACEMENT = String.valueOf((char) 0xFFFD);

    @Test
    void storableTextHasNoNulAndNoHalfCharacters() {
        assertThat(EmailText.storable(null)).isNull();
        String emoji = HIGH + LOW;
        assertThat(EmailText.storable("Plain ₹1,200.00 " + emoji)).isEqualTo("Plain ₹1,200.00 " + emoji);
        assertThat(EmailText.storable("a" + NUL + "b" + NUL)).isEqualTo("ab");
        assertThat(EmailText.storable("x" + HIGH + "y" + LOW + "z" + HIGH))
                .isEqualTo("x" + REPLACEMENT + "y" + REPLACEMENT + "z" + REPLACEMENT);
    }

    @Test
    void textCutToFitNeverEndsInHalfACharacter() {
        String emoji = HIGH + LOW;
        String text = "abcd" + emoji + "e";
        assertThat(EmailText.fit(text, 6)).isEqualTo("abcd…");
        assertThat(EmailText.fit(text, 5)).isEqualTo("abcd…");
        assertThat(EmailText.fit(text, 7)).isEqualTo(text);
        assertThat(EmailText.fit("abc" + emoji + "de", 6)).isEqualTo("abc" + emoji + "…");
        assertThat(EmailText.fit(null, 5)).isNull();

        assertThat(EmailText.start(text, 5)).isEqualTo("abcd");
        assertThat(EmailText.start(text, 6)).isEqualTo("abcd" + emoji);
        assertThat(EmailText.start(emoji, 1)).isEmpty();
        assertThat(EmailText.start(text, 0)).isEmpty();
    }

    @Test
    void aRecordsDateIsTheDayWhereTheReaderIs() {
        Instant lateUtc = Instant.parse("2026-09-16T23:52:00Z");
        assertThat(EmailText.date(lateUtc, ZoneOffset.ofHoursMinutes(5, 30))).isEqualTo("2026-09-17");
        assertThat(EmailText.date(lateUtc, ZoneOffset.UTC)).isEqualTo("2026-09-16");
        assertThat(EmailText.date(Instant.parse("2026-09-17T00:10:00Z"), ZoneOffset.ofHours(-5))).isEqualTo("2026-09-16");
        assertThat(EmailText.date((Instant) null, ZoneOffset.UTC)).isEmpty();
    }
}

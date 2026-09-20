package com.geneinvoice.document;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a size reads as next to a file. The unit has to be the one the printed number belongs to: a
 * byte short of a megabyte is 1023.999 KB, which is under the limit that chooses kilobytes but
 * prints, to one decimal, as a whole 1024 KB — a quantity that is not written that way (DOC-8).
 */
class DocumentSizeLabelTest {

    @Test
    void aSizeIsGivenInTheUnitItIsPrintedIn() {
        assertThat(DocumentDtos.sizeLabel(0)).isEqualTo("0 B");
        assertThat(DocumentDtos.sizeLabel(512)).isEqualTo("512 B");
        assertThat(DocumentDtos.sizeLabel(1023)).isEqualTo("1023 B");
        assertThat(DocumentDtos.sizeLabel(1024)).isEqualTo("1.0 KB");
        assertThat(DocumentDtos.sizeLabel(4096)).isEqualTo("4.0 KB");
        // The last size that still rounds to a kilobyte figure, and the first that does not.
        assertThat(DocumentDtos.sizeLabel(1_048_524)).isEqualTo("1023.9 KB");
        assertThat(DocumentDtos.sizeLabel(1_048_525)).isEqualTo("1.0 MB");
        assertThat(DocumentDtos.sizeLabel(1_048_575)).isEqualTo("1.0 MB");
        assertThat(DocumentDtos.sizeLabel(1_048_576)).isEqualTo("1.0 MB");
        assertThat(DocumentDtos.sizeLabel(10_485_760)).isEqualTo("10.0 MB");
    }

    /** No size at all prints 1024 of the smaller unit: that is one of the larger one. */
    @Test
    void noSizeReadsAsAThousandAndTwentyFourOfTheUnitBelow() {
        for (long bytes = 1_040_000; bytes <= 1_050_000; bytes++) {
            assertThat(DocumentDtos.sizeLabel(bytes)).as(bytes + " B").doesNotContain("1024.0 KB");
        }
    }
}

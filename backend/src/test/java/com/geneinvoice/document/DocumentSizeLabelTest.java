package com.geneinvoice.document;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentSizeLabelTest {

    @Test
    void aSizeIsGivenInTheUnitItIsPrintedIn() {
        assertThat(DocumentDtos.sizeLabel(0)).isEqualTo("0 B");
        assertThat(DocumentDtos.sizeLabel(512)).isEqualTo("512 B");
        assertThat(DocumentDtos.sizeLabel(1023)).isEqualTo("1023 B");
        assertThat(DocumentDtos.sizeLabel(1024)).isEqualTo("1.0 KB");
        assertThat(DocumentDtos.sizeLabel(4096)).isEqualTo("4.0 KB");
        assertThat(DocumentDtos.sizeLabel(1_048_524)).isEqualTo("1023.9 KB");
        assertThat(DocumentDtos.sizeLabel(1_048_525)).isEqualTo("1.0 MB");
        assertThat(DocumentDtos.sizeLabel(1_048_575)).isEqualTo("1.0 MB");
        assertThat(DocumentDtos.sizeLabel(1_048_576)).isEqualTo("1.0 MB");
        assertThat(DocumentDtos.sizeLabel(10_485_760)).isEqualTo("10.0 MB");
    }

    @Test
    void noSizeReadsAsAThousandAndTwentyFourOfTheUnitBelow() {
        for (long bytes = 1_040_000; bytes <= 1_050_000; bytes++) {
            assertThat(DocumentDtos.sizeLabel(bytes)).as(bytes + " B").doesNotContain("1024.0 KB");
        }
    }
}

package com.cadence.streaming.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ByteRangeTest {

    @Test
    void noHeaderMeansTheWholeResource() {
        ByteRange range = ByteRange.parse(null, 5000);

        assertThat(range).isEqualTo(new ByteRange(0, 4999, 5000));
        assertThat(range.isPartial()).isFalse();
    }

    @Test
    void closedOpenAndSuffixRanges() {
        assertThat(ByteRange.parse("bytes=0-1023", 5000)).isEqualTo(new ByteRange(0, 1023, 5000));
        assertThat(ByteRange.parse("bytes=4000-", 5000)).isEqualTo(new ByteRange(4000, 4999, 5000));
        assertThat(ByteRange.parse("bytes=-500", 5000)).isEqualTo(new ByteRange(4500, 4999, 5000));
        assertThat(ByteRange.parse("bytes=-9000", 5000)).isEqualTo(new ByteRange(0, 4999, 5000));
        assertThat(ByteRange.parse("bytes=100-999999", 5000)).isEqualTo(new ByteRange(100, 4999, 5000));
        assertThat(ByteRange.parse("bytes=0-1023", 5000).contentRange()).isEqualTo("bytes 0-1023/5000");
        assertThat(ByteRange.parse("bytes=0-1023", 5000).length()).isEqualTo(1024);
    }

    @ParameterizedTest
    @ValueSource(strings = {"bytes=5000-", "bytes=6000-7000", "bytes=10-5", "bytes=-0", "bytes=-", "items=0-10",
            "bytes=abc", "bytes=0-10,20-30", "bytes=99999999999999999999-"})
    void invalidOrUnsatisfiableRangesAreRejected(String header) {
        assertThatThrownBy(() -> ByteRange.parse(header, 5000)).isInstanceOf(ByteRange.InvalidRangeException.class);
    }
}

package com.geneinvoice.common.time;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every business "now" or "today" goes through the clock bean, {@link AppClock} or {@link Today}, so tests
 * can move time; protocol time goes through {@link WallClock}. Only {@code common/time} reads the system
 * clock itself.
 */
class ClockReadGuardTest {

    static final Path MAIN = Path.of("src/main/java");
    static final Path CLOCK_PACKAGE = Path.of("src/main/java/com/geneinvoice/common/time");

    static final String TIME_TYPES = "(Instant|LocalDate|LocalDateTime|LocalTime|OffsetDateTime|OffsetTime"
            + "|ZonedDateTime|Year|YearMonth|MonthDay)";

    static final List<Pattern> FORBIDDEN = List.of(
            // X.now() with no clock: Instant.now(), LocalDate.now(), ...; AppClock.now() is the sanctioned one.
            Pattern.compile("\\b" + TIME_TYPES + "\\s*\\.\\s*now\\s*\\(\\s*\\)"),
            // X.now(anything but a clock): a zone, however it is named, reads the system clock in that zone.
            Pattern.compile("\\b" + TIME_TYPES + "\\s*\\.\\s*now\\s*\\(\\s*+(?!\\)|(this\\s*\\.\\s*)?clock"
                    + "|AppClock\\s*\\.\\s*get\\s*\\(\\s*\\)|WallClock\\s*\\.\\s*UTC\\b)"),
            Pattern.compile("\\bCalendar\\s*\\.\\s*getInstance\\s*\\("),
            Pattern.compile("\\bnew\\s+(java\\.util\\.)?GregorianCalendar\\s*\\("),
            Pattern.compile("\\bnew\\s+(java\\.util\\.)?Date\\s*\\(\\s*\\)"),
            Pattern.compile("\\bSystem\\s*\\.\\s*currentTimeMillis\\s*\\("),
            Pattern.compile("\\bClock\\s*\\.\\s*(systemUTC|systemDefaultZone|system)\\s*\\("));

    @Test
    void nothingOutsideTheClockPackageReadsTheSystemClock() throws IOException {
        assertThat(MAIN).isDirectory();
        List<String> offences = new ArrayList<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                if (file.startsWith(CLOCK_PACKAGE)) continue;
                List<String> lines = Files.readAllLines(file);
                for (int i = 0; i < lines.size(); i++) {
                    if (offends(lines.get(i))) {
                        offences.add(MAIN.relativize(file) + ":" + (i + 1) + ": " + lines.get(i).trim());
                    }
                }
            }
        }
        assertThat(offences)
                .as("read time through the injected Clock, AppClock.now(), Today.date() or WallClock.UTC")
                .isEmpty();
    }

    @Test
    void theGuardRecognisesEveryWayOfReadingTheSystemClock() {
        assertThat(List.of(
                "Instant now = Instant.now();",
                "d.setResolvedAt(java.time.Instant.now());",
                "LocalDate.now()",
                "LocalDate.now(ZoneOffset.UTC)",
                "LocalDateTime.now(java.time.ZoneId.of(\"UTC\"))",
                "OffsetDateTime.now ( )",
                "Date now = new Date();",
                "long t = System.currentTimeMillis();",
                "Clock c = Clock.systemUTC();",
                "LocalDate.now(UTC)",
                "ZonedDateTime.now(zone)",
                "LocalDate.now(ZONE)",
                "Instant.now(Clock.systemUTC())",
                "Calendar cal = Calendar.getInstance();",
                "java.util.Calendar.getInstance(TimeZone.getTimeZone(\"UTC\"))",
                "Calendar cal = new GregorianCalendar();",
                "new java.util.GregorianCalendar(zone)"))
                .allSatisfy(line -> assertThat(offends(line)).as(line).isTrue());
        assertThat(List.of(
                "this.createdAt = AppClock.now();",
                "Instant now = clock.instant();",
                "LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC)",
                "LocalDate.now(clock)",
                "LocalDate.now(this.clock)",
                "LocalDate.now( clock )",
                "ZonedDateTime.now(clock.withZone(zone))",
                "LocalDate.now(AppClock.get())",
                "Date.from(WallClock.UTC.instant())",
                "Instant.now(WallClock.UTC)",
                "new Date(now.getTime() + expirationMs)"))
                .allSatisfy(line -> assertThat(offends(line)).as(line).isFalse());
    }

    static boolean offends(String line) {
        return FORBIDDEN.stream().anyMatch(p -> p.matcher(line).find());
    }
}

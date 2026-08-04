/*
 * Copyright (c) 2025, PostgreSQL Global Development Group
 * BSD-2-Clause licensed as part of the PostgreSQL JDBC Driver.
 *
 * Android adaptation: DBX replaces ManagementFactory with Runtime.maxMemory()
 * because Android does not provide the Java SE java.lang.management module.
 */

package org.postgresql.util;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Parses PostgreSQL's maxResultBuffer property without Java SE management APIs.
 */
public class PGPropertyMaxResultBufferParser {
    private static final Logger LOGGER =
            Logger.getLogger(PGPropertyMaxResultBufferParser.class.getName());
    private static final String[] PERCENT_PHRASES = {"p", "pct", "percent"};

    public static long parseProperty(String value) throws PSQLException {
        long result = -1;
        if (value != null) {
            if (containsPercent(value)) {
                result = parsePercent(value);
            } else if (!value.isEmpty()) {
                result = parseBytes(value);
            }
        }
        return adjustResultSize(result);
    }

    private static boolean containsPercent(String value) {
        return percentPhraseLength(value) != -1;
    }

    private static long parsePercent(String value) throws PSQLException {
        if (value.isEmpty()) {
            return -1;
        }
        int suffixLength = percentPhraseLength(value);
        if (suffixLength == -1) {
            throwParsingException(value);
        }
        try {
            double percentage = Double.parseDouble(
                    value.substring(0, value.length() - suffixLength)) / 100.0;
            return (long) (percentage * maximumHeapBytes());
        } catch (NumberFormatException error) {
            throwParsingException(value);
            return -1;
        }
    }

    private static int percentPhraseLength(String value) {
        int result = -1;
        for (String phrase : PERCENT_PHRASES) {
            if (value.length() > phrase.length()
                    && value.substring(value.length() - phrase.length()).equals(phrase)) {
                result = phrase.length();
            }
        }
        return result;
    }

    private static long parseBytes(String value) throws PSQLException {
        long multiplier = 1;
        long thousand = 1_000;
        char suffix = value.charAt(value.length() - 1);
        try {
            switch (suffix) {
                case 'T':
                case 't':
                    multiplier *= thousand;
                case 'G':
                case 'g':
                    multiplier *= thousand;
                case 'M':
                case 'm':
                    multiplier *= thousand;
                case 'K':
                case 'k':
                    multiplier *= thousand;
                    return Long.parseLong(value.substring(0, value.length() - 1))
                            * multiplier;
                case '%':
                    return -1;
                default:
                    if (suffix >= '0' && suffix <= '9') {
                        return Long.parseLong(value);
                    }
                    throwParsingException(value);
                    return -1;
            }
        } catch (NumberFormatException error) {
            throwParsingException(value);
            return -1;
        }
    }

    private static long adjustResultSize(long requestedBytes) {
        long maximum = maximumHeapBytes();
        if (requestedBytes > 0.9 * maximum) {
            long limit = (long) (0.9 * maximum);
            LOGGER.log(
                    Level.WARNING,
                    GT.tr(
                            "WARNING! Required to allocate {0} bytes, which exceeded possible heap memory size. "
                                    + "Assigned {1} bytes as limit.",
                            String.valueOf(requestedBytes),
                            String.valueOf(limit)));
            return limit;
        }
        return requestedBytes;
    }

    private static long maximumHeapBytes() {
        return Runtime.getRuntime().maxMemory();
    }

    private static void throwParsingException(String value) throws PSQLException {
        throw new PSQLException(
                GT.tr(
                        "Received MaxResultBuffer parameter can't be parsed. "
                                + "Value received to parse: {0}",
                        value),
                PSQLState.SYNTAX_ERROR);
    }
}

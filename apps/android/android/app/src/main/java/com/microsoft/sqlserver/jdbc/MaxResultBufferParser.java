/*
 * Microsoft JDBC Driver for SQL Server Copyright(c) Microsoft Corporation.
 * Licensed under the MIT License.
 *
 * Android adaptation: DBX replaces ManagementFactory with Runtime.maxMemory()
 * because Android does not provide the Java SE java.lang.management module.
 */

package com.microsoft.sqlserver.jdbc;

import java.text.MessageFormat;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Parses the SQL Server maxResultBuffer connection property without depending
 * on Java SE management APIs that are unavailable on Android.
 */
public class MaxResultBufferParser {
    private static final Logger LOGGER = Logger.getLogger("com.microsoft.sqlserver.jdbc.MaxResultBufferParser");
    private static final String[] PERCENT_PHRASES = {"percent", "pct", "p"};
    private static final String ERROR_MESSAGE = "MaxResultBuffer property is badly formatted: {0}.";

    private MaxResultBufferParser() {}

    public static long validateMaxResultBuffer(String input) throws SQLServerException {
        if (StringUtils.isEmpty(input) || "-1".equals(input)) {
            return -1;
        }
        if (input.matches("-?\\d+")) {
            try {
                return adjustMemory(Long.parseLong(input), 1);
            } catch (NumberFormatException error) {
                throwInvalidValue(error, input);
                return -1;
            }
        }

        for (String suffix : PERCENT_PHRASES) {
            if (input.endsWith(suffix)) {
                String number = input.substring(0, input.length() - suffix.length());
                try {
                    return adjustMemoryPercentage(Long.parseLong(number));
                } catch (NumberFormatException error) {
                    throwInvalidValue(error, number);
                    return -1;
                }
            }
        }

        long multiplier = multiplier(input);
        String number = input.substring(0, input.length() - 1);
        try {
            return adjustMemory(Long.parseLong(number), multiplier);
        } catch (NumberFormatException error) {
            throwInvalidValue(error, number);
            return -1;
        }
    }

    private static long multiplier(String input) throws SQLServerException {
        long multiplier;
        switch (Character.toUpperCase(input.charAt(input.length() - 1))) {
            case 'K':
                multiplier = 1_000L;
                break;
            case 'M':
                multiplier = 1_000_000L;
                break;
            case 'G':
                multiplier = 1_000_000_000L;
                break;
            case 'T':
                multiplier = 1_000_000_000_000L;
                break;
            default:
                throwInvalidValue(null, input);
                return 1;
        }
        return multiplier;
    }

    private static long adjustMemoryPercentage(long percentage) throws SQLServerException {
        requirePositive(percentage);
        long maximum = maximumHeapBytes();
        return percentage > 90
                ? (long) (0.9 * maximum)
                : (long) (percentage / 100.0 * maximum);
    }

    private static long adjustMemory(long size, long multiplier) throws SQLServerException {
        requirePositive(size);
        long maximum = maximumHeapBytes();
        if (size > Long.MAX_VALUE / multiplier || size * multiplier > 0.9 * maximum) {
            return (long) (0.9 * maximum);
        }
        return size * multiplier;
    }

    private static void requirePositive(long value) throws SQLServerException {
        if (value <= 0) {
            Object[] arguments = {value};
            MessageFormat format = new MessageFormat(
                    SQLServerException.getErrString("R_maxResultBufferNegativeParameterValue"));
            throw new SQLServerException(format.format(arguments), new Throwable());
        }
    }

    private static long maximumHeapBytes() {
        return Runtime.getRuntime().maxMemory();
    }

    private static void throwInvalidValue(Throwable cause, Object... arguments) throws SQLServerException {
        if (LOGGER.isLoggable(Level.SEVERE)) {
            LOGGER.log(Level.SEVERE, ERROR_MESSAGE, arguments);
        }
        MessageFormat format = new MessageFormat(
                SQLServerException.getErrString("R_maxResultBufferInvalidSyntax"));
        throw new SQLServerException(format.format(arguments), cause);
    }
}

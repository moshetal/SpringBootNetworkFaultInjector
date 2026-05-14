package com.mta.faultinjection.web;

/**
 * Textually replaces the top-level {@code fault:} block in a YAML document without
 * parsing the whole file, so surrounding sections and comments are preserved.
 */
public final class FaultInjectorYamlFaultBlockSplicer {

    private FaultInjectorYamlFaultBlockSplicer() {}

    public static String spliceFaultBlock(String original, String liveBlock) {
        String normalized = original.replace("\r\n", "\n").replace('\r', '\n');
        String[] lines = normalized.split("\n", -1);

        int faultStart = -1;
        for (int i = 0; i < lines.length; i++) {
            if (isTopLevelFaultLine(lines[i])) {
                faultStart = i;
                break;
            }
        }

        if (faultStart < 0) {
            StringBuilder sb = new StringBuilder(normalized);
            if (!normalized.isEmpty() && !normalized.endsWith("\n")) {
                sb.append('\n');
            }
            if (!normalized.endsWith("\n\n") && !normalized.isEmpty()) {
                sb.append('\n');
            }
            sb.append(liveBlock);
            if (!liveBlock.endsWith("\n")) {
                sb.append('\n');
            }
            return sb.toString();
        }

        int faultEnd = lines.length;
        for (int i = faultStart + 1; i < lines.length; i++) {
            if (isTopLevelKeyLine(lines[i])) {
                faultEnd = i;
                break;
            }
        }

        StringBuilder out = new StringBuilder();
        for (int i = 0; i < faultStart; i++) {
            out.append(lines[i]).append('\n');
        }
        out.append(liveBlock);
        if (!liveBlock.endsWith("\n")) {
            out.append('\n');
        }
        for (int i = faultEnd; i < lines.length; i++) {
            out.append(lines[i]);
            if (i < lines.length - 1) {
                out.append('\n');
            }
        }
        if (normalized.endsWith("\n") && (out.length() == 0 || out.charAt(out.length() - 1) != '\n')) {
            out.append('\n');
        }
        return out.toString();
    }

    private static boolean isTopLevelFaultLine(String line) {
        if (line == null || line.isEmpty() || Character.isWhitespace(line.charAt(0))) {
            return false;
        }
        int colon = line.indexOf(':');
        if (colon < 0) {
            return false;
        }
        String trimmedKey = line.substring(0, colon).trim();
        return "fault".equals(trimmedKey);
    }

    private static boolean isTopLevelKeyLine(String line) {
        if (line == null || line.isEmpty()) {
            return false;
        }
        char c = line.charAt(0);
        if (Character.isWhitespace(c) || c == '#' || c == '-') {
            return false;
        }
        return line.contains(":");
    }
}

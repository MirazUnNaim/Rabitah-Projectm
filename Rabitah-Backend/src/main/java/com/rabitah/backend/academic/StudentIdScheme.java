package com.rabitah.backend.academic;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Campus student code: BB00DDSRR.
 * BB is the admission batch, DD is the department number, S is section (1/A or 2/B), and RR is roll 01–60.
 */
public final class StudentIdScheme {
    public static final int MIN_BATCH = 21;
    public static final int MAX_BATCH = 24;
    public static final int MIN_ROLL = 1;
    public static final int MAX_ROLL = 60;

    private static final Map<String, Integer> DEPARTMENT_NUMBERS = Map.of(
            "MPE", 11,
            "EEE", 21,
            "CSE", 41,
            "CEE", 51);
    private static final Map<Integer, String> DEPARTMENTS = Map.of(
            11, "MPE",
            21, "EEE",
            41, "CSE",
            51, "CEE");

    private StudentIdScheme() {}

    public static String format(int batch, String department, int section, int roll) {
        Integer departmentNumber = DEPARTMENT_NUMBERS.get(normalizeDepartment(department));
        if (departmentNumber == null || batch < MIN_BATCH || batch > MAX_BATCH || section < 1 || section > 2
                || roll < MIN_ROLL || roll > MAX_ROLL) {
            throw new IllegalArgumentException("Student code parts are outside the supported campus range.");
        }
        return "%02d00%02d%d%02d".formatted(batch, departmentNumber, section, roll);
    }

    public static Optional<Parts> parse(String raw) {
        if (raw == null || !raw.matches("\\d{9}")) {
            return Optional.empty();
        }
        try {
            int batch = Integer.parseInt(raw.substring(0, 2));
            int departmentNumber = Integer.parseInt(raw.substring(4, 6));
            int section = Integer.parseInt(raw.substring(6, 7));
            int roll = Integer.parseInt(raw.substring(7, 9));
            String department = DEPARTMENTS.get(departmentNumber);
            if (department == null || batch < MIN_BATCH || batch > MAX_BATCH || section < 1 || section > 2
                    || roll < MIN_ROLL || roll > MAX_ROLL) {
                return Optional.empty();
            }
            return Optional.of(new Parts(batch, department, section, roll));
        } catch (NumberFormatException ignored) {
            return Optional.empty();
        }
    }

    public static boolean matchesScope(String studentId, String department, String section) {
        return parse(studentId).map(parts -> parts.department().equals(normalizeDepartment(department))
                && parts.section() == sectionNumber(section)).orElse(false);
    }

    public static int sectionNumber(String section) {
        return switch (section == null ? "" : section.trim().toUpperCase(Locale.ROOT)) {
            case "A", "1" -> 1;
            case "B", "2" -> 2;
            default -> 0;
        };
    }

    public static String sectionCode(int section) {
        return switch (section) {
            case 1 -> "A";
            case 2 -> "B";
            default -> throw new IllegalArgumentException("Section must be 1 or 2.");
        };
    }

    public static int departmentNumber(String department) {
        Integer number = DEPARTMENT_NUMBERS.get(normalizeDepartment(department));
        if (number == null) {
            throw new IllegalArgumentException("Unsupported department: " + department);
        }
        return number;
    }

    private static String normalizeDepartment(String department) {
        return department == null ? "" : department.trim().toUpperCase(Locale.ROOT);
    }

    public record Parts(int batch, String department, int section, int roll) {}
}

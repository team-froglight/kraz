package me.methodial.kraz.selftest;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Collects pass/fail results for the report. */
final class Checks {
    final List<String> lines = new ArrayList<>();
    int passed;
    int failed;

    void section(String title) {
        lines.add("");
        lines.add("== " + title);
    }

    void info(String text) {
        lines.add("       " + text);
    }

    void check(String name, boolean ok) {
        check(name, ok, null);
    }

    void check(String name, boolean ok, String detail) {
        if (ok) {
            passed++;
        } else {
            failed++;
        }
        lines.add((ok ? "PASS   " : "FAIL   ") + name + (detail == null ? "" : "  [" + detail + "]"));
    }

    void eq(String name, Object expected, Object actual) {
        check(name, Objects.equals(expected, actual), "expected " + expected + ", got " + actual);
    }

    void near(String name, double expected, double actual, double epsilon) {
        check(name, Math.abs(expected - actual) <= epsilon, "expected " + expected + ", got " + actual);
    }

    void error(String name, Throwable t) {
        failed++;
        StringWriter trace = new StringWriter();
        t.printStackTrace(new PrintWriter(trace));
        lines.add("FAIL   " + name + " threw:");
        lines.add(trace.toString());
    }
}

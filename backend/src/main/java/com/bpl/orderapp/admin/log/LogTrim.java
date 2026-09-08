package com.bpl.orderapp.admin.log;
import java.util.*;
public class LogTrim {
    // §7.3 / §3.1: trim per application to latest 500 lines using internal monotonic line_number
    public List<String> trimTo500(List<String> lines, long lastLineNumber) {
        // Each appended line gets line_number = lastLineNumber + i (monotonic, internal)
        return lines.subList(Math.max(0, lines.size() - 500), lines.size());
    }
}

package com.bpl.orderapp.admin.log;
import java.util.*;
public class LogDedup {
    // §7.2 exact algorithm
    public List<String> dedup(List<String> newBatch, String lastSavedContent) {
        List<String> result = new ArrayList<>();
        if (lastSavedContent == null || lastSavedContent.isEmpty()) return new ArrayList<>(newBatch); // fallback: all new
        int matchIdx = -1;
        for (int i = newBatch.size() - 1; i >= 0; i--) {
            if (newBatch.get(i).equals(lastSavedContent)) { matchIdx = i; break; }
        }
        if (matchIdx == -1) return new ArrayList<>(newBatch); // fallback §7.2 point 6
        for (int i = matchIdx + 1; i < newBatch.size(); i++) result.add(newBatch.get(i));
        return result;
    }
}

package com.anthropic.eclipse.claude.model;

/**
 * Tracks cumulative token usage, cost, and duration for a conversation session.
 */
public class UsageInfo {

    private int totalInputTokens;
    private int totalOutputTokens;
    private double totalCostUsd;
    private long totalDurationMs;
    private int totalTurns;

    public UsageInfo() {}

    /**
     * Add usage from a single query result.
     */
    public void addUsage(int inputTokens, int outputTokens, double costUsd, long durationMs, int turns) {
        this.totalInputTokens += inputTokens;
        this.totalOutputTokens += outputTokens;
        this.totalCostUsd += costUsd;
        this.totalDurationMs += durationMs;
        this.totalTurns += turns;
    }

    /**
     * Reset all counters.
     */
    public void reset() {
        totalInputTokens = 0;
        totalOutputTokens = 0;
        totalCostUsd = 0;
        totalDurationMs = 0;
        totalTurns = 0;
    }

    public int getTotalInputTokens() { return totalInputTokens; }
    public int getTotalOutputTokens() { return totalOutputTokens; }
    public int getTotalTokens() { return totalInputTokens + totalOutputTokens; }
    public double getTotalCostUsd() { return totalCostUsd; }
    public long getTotalDurationMs() { return totalDurationMs; }
    public int getTotalTurns() { return totalTurns; }

    /**
     * Format cost as a readable string.
     */
    public String formatCost() {
        if (totalCostUsd < 0.01) {
            return String.format("$%.4f", totalCostUsd);
        }
        return String.format("$%.2f", totalCostUsd);
    }

    /**
     * Format tokens as a readable string.
     */
    public String formatTokens() {
        return String.format("%,d in / %,d out", totalInputTokens, totalOutputTokens);
    }

    /**
     * Format duration as a readable string.
     */
    public String formatDuration() {
        if (totalDurationMs < 1000) {
            return totalDurationMs + "ms";
        }
        double seconds = totalDurationMs / 1000.0;
        if (seconds < 60) {
            return String.format("%.1fs", seconds);
        }
        int minutes = (int) (seconds / 60);
        int secs = (int) (seconds % 60);
        return String.format("%dm %ds", minutes, secs);
    }

    @Override
    public String toString() {
        return String.format("Tokens: %s | Cost: %s | Duration: %s | Turns: %d",
            formatTokens(), formatCost(), formatDuration(), totalTurns);
    }
}

package com.anthropic.eclipse.claude.model;

import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * Session metadata for tracking and resuming Claude conversations.
 */
public class SessionInfo {

    private String sessionId;
    private String model;
    private String workingDirectory;
    private long startTime;
    private long lastActiveTime;
    private String permissionMode;
    private int messageCount;
    private String summary; // Brief description of the conversation

    public SessionInfo() {
        this.startTime = System.currentTimeMillis();
        this.lastActiveTime = this.startTime;
    }

    public SessionInfo(String sessionId) {
        this();
        this.sessionId = sessionId;
    }

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    public String getWorkingDirectory() { return workingDirectory; }
    public void setWorkingDirectory(String workingDirectory) { this.workingDirectory = workingDirectory; }

    public long getStartTime() { return startTime; }
    public void setStartTime(long startTime) { this.startTime = startTime; }

    public long getLastActiveTime() { return lastActiveTime; }
    public void setLastActiveTime(long lastActiveTime) { this.lastActiveTime = lastActiveTime; }

    public String getPermissionMode() { return permissionMode; }
    public void setPermissionMode(String permissionMode) { this.permissionMode = permissionMode; }

    public int getMessageCount() { return messageCount; }
    public void setMessageCount(int messageCount) { this.messageCount = messageCount; }

    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }

    /**
     * Update the last active time to now.
     */
    public void touch() {
        this.lastActiveTime = System.currentTimeMillis();
    }

    private static final SimpleDateFormat DISPLAY_DATE_FORMAT = new SimpleDateFormat("dd/MM/yy HH:mm");

    /**
     * Get a display label for this session, including the date and summary.
     * Format: "[dd/MM/yy HH:mm]  Summary text"
     */
    public String getDisplayLabel() {
        String desc;
        if (summary != null && !summary.isEmpty()) {
            desc = summary;
        } else if (sessionId != null && sessionId.length() > 8) {
            desc = "Session " + sessionId.substring(0, 8) + "...";
        } else {
            desc = "Session " + (sessionId != null ? sessionId : "unknown");
        }

        if (lastActiveTime > 0) {
            String dateStr = DISPLAY_DATE_FORMAT.format(new Date(lastActiveTime));
            return "[" + dateStr + "]  " + desc;
        }
        return desc;
    }

    @Override
    public String toString() {
        return "SessionInfo{" +
            "id=" + sessionId +
            ", model=" + model +
            ", cwd=" + workingDirectory +
            ", messages=" + messageCount +
            "}";
    }
}

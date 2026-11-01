package org.telegram.luckgram.ai;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.luckgram.LuckgramAI;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * One local AI conversation (spec 18). Lives only on the device, never as a Telegram chat.
 *
 * <p>Extension point "conversation properties" (spec 18 需求 8): {@link #task} and
 * {@link #systemPrompt} are sent with every request of this conversation; {@link #key} lets a
 * feature find its own conversation again (e.g. one per agent, spec 22) through
 * {@link LuckgramAiStore#findOrCreate}.
 */
public final class LuckgramAiConversation {

    /** Messages sent as context with each request (spec 18 需求 6). */
    public static final int CONTEXT_MESSAGES = 20;

    public String id;
    /** Optional stable key set by the feature that created it; null for normal conversations. */
    public String key;
    public String title;
    /** {@code task} of /v1/ai/chat, see {@link LuckgramAI#TASK_CHAT} and friends. */
    public String task = LuckgramAI.TASK_CHAT;
    /** Extra system instruction sent first with every request, or null. */
    public String systemPrompt;
    public long createdAt;
    public long updatedAt;
    public final ArrayList<Message> messages = new ArrayList<>();

    public static final class Message {
        public static final String ROLE_USER = "user";
        public static final String ROLE_ASSISTANT = "assistant";
        /** Plain text (Markdown for assistant replies). Other types come from the message type registry. */
        public static final String TYPE_TEXT = "text";

        public String id;
        public String role;
        public String type = TYPE_TEXT;
        public String text;
        /** Free-form payload for registered message types (e.g. an image path). */
        public JSONObject data;
        /** Set when the request failed: a {@link org.telegram.luckgram.LuckgramAIError} code. */
        public String errorCode;
        public long time;
        /** True while the reply is streaming in (not saved). */
        public transient boolean pending;

        public static Message create(String role, String type, String text) {
            Message m = new Message();
            m.id = UUID.randomUUID().toString();
            m.role = role;
            m.type = type == null ? TYPE_TEXT : type;
            m.text = text;
            m.time = System.currentTimeMillis();
            return m;
        }

        public boolean isUser() {
            return ROLE_USER.equals(role);
        }

        JSONObject toJson() throws Exception {
            JSONObject o = new JSONObject();
            o.put("id", id).put("role", role).put("type", type).put("time", time);
            if (text != null) o.put("text", text);
            if (data != null) o.put("data", data);
            if (errorCode != null) o.put("error", errorCode);
            return o;
        }

        static Message fromJson(JSONObject o) {
            Message m = new Message();
            m.id = o.optString("id", UUID.randomUUID().toString());
            m.role = o.optString("role", ROLE_USER);
            m.type = o.optString("type", TYPE_TEXT);
            m.text = o.has("text") ? o.optString("text") : null;
            m.data = o.optJSONObject("data");
            m.errorCode = o.has("error") ? o.optString("error") : null;
            m.time = o.optLong("time");
            return m;
        }
    }

    static LuckgramAiConversation create(String task, String systemPrompt, String title) {
        LuckgramAiConversation c = new LuckgramAiConversation();
        c.id = UUID.randomUUID().toString();
        if (task != null) c.task = task;
        c.systemPrompt = systemPrompt;
        c.title = title;
        c.createdAt = c.updatedAt = System.currentTimeMillis();
        return c;
    }

    public Message find(String messageId) {
        for (Message m : messages) {
            if (m.id.equals(messageId)) {
                return m;
            }
        }
        return null;
    }

    /**
     * The request messages: the system instruction, then the last {@link #CONTEXT_MESSAGES}
     * messages that have text for the model (failed and pending ones are skipped), oldest dropped
     * first if the total would go over {@code maxChars} (the server's ai.max_input_chars).
     */
    public List<LuckgramAI.Message> requestMessages(int maxChars) {
        ArrayList<LuckgramAI.Message> picked = new ArrayList<>();
        int chars = systemPrompt == null ? 0 : systemPrompt.length();
        int count = 0;
        for (int i = messages.size() - 1; i >= 0 && count < CONTEXT_MESSAGES; i--) {
            Message m = messages.get(i);
            if (m.pending || m.errorCode != null) {
                continue;
            }
            String text = LuckgramAiRegistry.contextText(m);
            if (text == null || text.isEmpty()) {
                continue;
            }
            if (chars + text.length() > maxChars) {
                if (picked.isEmpty()) {
                    // the newest message alone is too long: send its end, the server would reject all of it
                    text = text.substring(text.length() - Math.max(0, maxChars - chars));
                } else {
                    break;
                }
            }
            chars += text.length();
            picked.add(0, m.isUser() ? LuckgramAI.Message.user(text) : LuckgramAI.Message.assistant(text));
            count++;
        }
        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            picked.add(0, LuckgramAI.Message.system(systemPrompt));
        }
        return picked;
    }

    JSONObject toJson() throws Exception {
        JSONObject o = new JSONObject();
        o.put("id", id).put("task", task).put("created", createdAt).put("updated", updatedAt);
        if (key != null) o.put("key", key);
        if (title != null) o.put("title", title);
        if (systemPrompt != null) o.put("system", systemPrompt);
        JSONArray arr = new JSONArray();
        for (Message m : messages) {
            if (!m.pending) {
                arr.put(m.toJson());
            }
        }
        o.put("messages", arr);
        return o;
    }

    static LuckgramAiConversation fromJson(JSONObject o) {
        LuckgramAiConversation c = new LuckgramAiConversation();
        c.id = o.optString("id", UUID.randomUUID().toString());
        c.key = o.has("key") ? o.optString("key") : null;
        c.title = o.has("title") ? o.optString("title") : null;
        c.task = o.optString("task", LuckgramAI.TASK_CHAT);
        c.systemPrompt = o.has("system") ? o.optString("system") : null;
        c.createdAt = o.optLong("created");
        c.updatedAt = o.optLong("updated", c.createdAt);
        JSONArray arr = o.optJSONArray("messages");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject m = arr.optJSONObject(i);
                if (m != null) {
                    c.messages.add(Message.fromJson(m));
                }
            }
        }
        return c;
    }
}

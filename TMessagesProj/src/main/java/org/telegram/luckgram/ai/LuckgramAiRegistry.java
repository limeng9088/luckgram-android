package org.telegram.luckgram.ai;

import android.content.Context;
import android.view.View;

import org.telegram.luckgram.LuckgramStrings;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

/**
 * Extension points of the AI assistant (spec 18 需求 8). Other features (22 agents, 23 images)
 * register here, typically from a static initializer called once at startup, and never change the
 * assistant's own code. Call everything on the UI thread.
 *
 * <h3>1. Quick buttons above the input field</h3>
 * <pre>
 * LuckgramAiRegistry.registerQuickAction(new LuckgramAiRegistry.QuickAction("image",
 *     "画图", "Draw", R.drawable.msg_photos, host -&gt; host.setInput("画 ")));
 * </pre>
 *
 * <h3>2. Input interceptors</h3> take over messages starting with a prefix (case-insensitive):
 * <pre>
 * LuckgramAiRegistry.registerInputInterceptor(new String[]{"画", "/image"}, (host, prefix, rest) -&gt; {
 *     host.addUserMessage(prefix + rest);
 *     ... host.addMessage(...);
 *     return true; // handled; false = send to the model as usual
 * });
 * </pre>
 *
 * <h3>3. Entries at the top of the assistant page</h3>
 * <pre>
 * LuckgramAiRegistry.registerHeaderEntry(new LuckgramAiRegistry.HeaderEntry("agents",
 *     "智能体", "Agents", R.drawable.msg_bots, fragment -&gt; fragment.presentFragment(new AgentsActivity())));
 * </pre>
 *
 * <h3>4. Conversation properties</h3> see {@link LuckgramAiConversation#task},
 * {@link LuckgramAiConversation#systemPrompt}, {@link LuckgramAiStore#findOrCreate} and
 * {@link LuckgramAiChatActivity#open(BaseFragment, String)}.
 *
 * <h3>5. Message types</h3> stored with {@link LuckgramAiConversation.Message#type} and
 * {@link LuckgramAiConversation.Message#data}; unregistered types show a placeholder.
 * <pre>
 * LuckgramAiRegistry.registerMessageType("image", new LuckgramAiRegistry.MessageRenderer() { ... });
 * </pre>
 */
public final class LuckgramAiRegistry {

    private LuckgramAiRegistry() {
    }

    /** Optional condition for showing a quick button or header entry (checked each time the page opens). */
    public interface Visibility {
        boolean isVisible();
    }

    /** What an extension can do with the open assistant page. */
    public interface ChatHost {
        int getCurrentAccount();

        Context getContext();

        BaseFragment getFragment();

        LuckgramAiConversation getConversation();

        String getInput();

        void setInput(CharSequence text);

        /** Appends a message to the conversation, shows it and saves. */
        void addMessage(LuckgramAiConversation.Message message);

        /** Shows the changes to a message already in the conversation and saves. */
        void updateMessage(LuckgramAiConversation.Message message);
    }

    // ---------------------------------------------------------------- 1. quick actions

    public interface QuickActionCallback {
        void run(ChatHost host);
    }

    public static final class QuickAction {
        public final String id;
        final String titleZh, titleEn;
        /** Drawable resource, or 0 for text only. */
        public final int icon;
        public final QuickActionCallback callback;
        Visibility visibility;

        public QuickAction visibleWhen(Visibility visibility) {
            this.visibility = visibility;
            return this;
        }

        public boolean isVisible() {
            return visibility == null || visibility.isVisible();
        }

        public QuickAction(String id, String titleZh, String titleEn, int icon, QuickActionCallback callback) {
            this.id = id;
            this.titleZh = titleZh;
            this.titleEn = titleEn;
            this.icon = icon;
            this.callback = callback;
        }

        public String title() {
            return LuckgramStrings.get(titleZh, titleEn);
        }
    }

    // ---------------------------------------------------------------- 2. input interceptors

    public interface InputInterceptor {
        /**
         * @param prefix the registered prefix that matched, as registered
         * @param rest   the text after the prefix, trimmed
         * @return true if handled (nothing is sent to the model)
         */
        boolean intercept(ChatHost host, String prefix, String rest);
    }

    private static final class Interceptor {
        final String[] prefixes;
        final InputInterceptor handler;

        Interceptor(String[] prefixes, InputInterceptor handler) {
            this.prefixes = prefixes;
            this.handler = handler;
        }
    }

    // ---------------------------------------------------------------- 3. header entries

    public interface HeaderCallback {
        void run(BaseFragment assistantPage);
    }

    public static final class HeaderEntry {
        public final String id;
        final String titleZh, titleEn;
        public final int icon;
        public final HeaderCallback callback;
        Visibility visibility;

        public HeaderEntry visibleWhen(Visibility visibility) {
            this.visibility = visibility;
            return this;
        }

        public boolean isVisible() {
            return visibility == null || visibility.isVisible();
        }

        public HeaderEntry(String id, String titleZh, String titleEn, int icon, HeaderCallback callback) {
            this.id = id;
            this.titleZh = titleZh;
            this.titleEn = titleEn;
            this.icon = icon;
            this.callback = callback;
        }

        public String title() {
            return LuckgramStrings.get(titleZh, titleEn);
        }
    }

    // ---------------------------------------------------------------- 5. message types

    public interface MessageRenderer {
        /** Creates the bubble content view; it is placed inside the standard bubble. */
        View createView(Context context, Theme.ResourcesProvider resourcesProvider);

        void bind(View view, LuckgramAiConversation.Message message, boolean outgoing);

        /** Text sent to the model as context for this message, or null to leave it out. */
        default String contextText(LuckgramAiConversation.Message message) {
            return message.text;
        }

        /** Text copied by "Copy" in the long-press menu, or null to hide it. */
        default String copyText(LuckgramAiConversation.Message message) {
            return message.text;
        }
    }

    // ---------------------------------------------------------------- storage

    private static final ArrayList<QuickAction> quickActions = new ArrayList<>();
    private static final ArrayList<Interceptor> interceptors = new ArrayList<>();
    private static final ArrayList<HeaderEntry> headerEntries = new ArrayList<>();
    private static final LinkedHashMap<String, MessageRenderer> renderers = new LinkedHashMap<>();

    static {
        // spec 18 需求 3: built-in quick buttons fill a prompt prefix into the input field
        registerQuickAction(new QuickAction("summary", "总结", "Summarize", 0, host -> fillPrefix(host, LuckgramStrings.get("总结一下：", "Summarize this: "))));
        registerQuickAction(new QuickAction("rewrite", "改写", "Rewrite", 0, host -> fillPrefix(host, LuckgramStrings.get("改写一下：", "Rewrite this: "))));
        registerQuickAction(new QuickAction("explain", "讲解", "Explain", 0, host -> fillPrefix(host, LuckgramStrings.get("讲解一下：", "Explain this: "))));
        registerQuickAction(new QuickAction("solve", "解题", "Solve", 0, host -> fillPrefix(host, LuckgramStrings.get("解这道题：", "Solve this: "))));
        LuckgramAiDebugDemo.register();
        // ---- features that extend the assistant register here ----
        org.telegram.luckgram.agents.LuckgramAgentsActivity.register(); // 22 agents
        org.telegram.luckgram.image.LuckgramAiImage.register(); // 23 images
    }

    /** Puts {@code prefix} in front of what is already typed (replacing another built-in prefix). */
    static void fillPrefix(ChatHost host, String prefix) {
        String current = host.getInput();
        if (current == null) current = "";
        for (String p : new String[]{"总结一下：", "改写一下：", "讲解一下：", "解这道题：", "Summarize this: ", "Rewrite this: ", "Explain this: ", "Solve this: "}) {
            if (current.startsWith(p)) {
                current = current.substring(p.length());
                break;
            }
        }
        host.setInput(prefix + current);
    }

    /** Replaces an action with the same id. */
    public static void registerQuickAction(QuickAction action) {
        unregisterQuickAction(action.id);
        quickActions.add(action);
    }

    public static void unregisterQuickAction(String id) {
        for (int i = quickActions.size() - 1; i >= 0; i--) {
            if (quickActions.get(i).id.equals(id)) quickActions.remove(i);
        }
    }

    /** Visible quick actions, in registration order. */
    public static List<QuickAction> quickActions() {
        ArrayList<QuickAction> out = new ArrayList<>();
        for (QuickAction a : quickActions) {
            if (a.isVisible()) out.add(a);
        }
        return out;
    }

    public static void registerInputInterceptor(String[] prefixes, InputInterceptor handler) {
        interceptors.add(new Interceptor(prefixes, handler));
    }

    public static void unregisterInputInterceptor(InputInterceptor handler) {
        for (int i = interceptors.size() - 1; i >= 0; i--) {
            if (interceptors.get(i).handler == handler) interceptors.remove(i);
        }
    }

    /** Runs the first interceptor whose prefix matches; true if it handled the text. */
    static boolean intercept(ChatHost host, String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        for (Interceptor i : new ArrayList<>(interceptors)) {
            for (String p : i.prefixes) {
                if (lower.startsWith(p.toLowerCase(Locale.ROOT))) {
                    if (i.handler.intercept(host, p, text.substring(p.length()).trim())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    public static void registerHeaderEntry(HeaderEntry entry) {
        unregisterHeaderEntry(entry.id);
        headerEntries.add(entry);
    }

    public static void unregisterHeaderEntry(String id) {
        for (int i = headerEntries.size() - 1; i >= 0; i--) {
            if (headerEntries.get(i).id.equals(id)) headerEntries.remove(i);
        }
    }

    /** Visible header entries, in registration order. */
    public static List<HeaderEntry> headerEntries() {
        ArrayList<HeaderEntry> out = new ArrayList<>();
        for (HeaderEntry e : headerEntries) {
            if (e.isVisible()) out.add(e);
        }
        return out;
    }

    public static void registerMessageType(String type, MessageRenderer renderer) {
        renderers.put(type, renderer);
    }

    public static MessageRenderer renderer(String type) {
        return type == null ? null : renderers.get(type);
    }

    static List<String> messageTypes() {
        return new ArrayList<>(renderers.keySet());
    }

    /** Context text for the model: text messages as they are, others via their renderer (none if unregistered). */
    static String contextText(LuckgramAiConversation.Message m) {
        if (m.type == null || LuckgramAiConversation.Message.TYPE_TEXT.equals(m.type)) {
            return m.text;
        }
        MessageRenderer r = renderers.get(m.type);
        return r == null ? null : r.contextText(m);
    }
}

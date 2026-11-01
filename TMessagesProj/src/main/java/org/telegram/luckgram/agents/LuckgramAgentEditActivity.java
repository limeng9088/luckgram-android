package org.telegram.luckgram.agents;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.luckgram.LuckgramStrings;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;

/** spec 22 需求 4: create or edit a custom agent (name ≤ 30, description, instruction ≤ 4000, color). */
public class LuckgramAgentEditActivity extends BaseFragment {

    private static final int MENU_DONE = 1;

    private final String agentId;
    private LuckgramAgents.Agent agent;
    private EditTextBoldCursor nameField, descField, promptField;
    private LinearLayout colorRow;
    private int color;

    /** @param agentId an existing custom agent, or null for a new one */
    public LuckgramAgentEditActivity(String agentId) {
        super();
        this.agentId = agentId;
    }

    @Override
    public boolean onFragmentCreate() {
        if (agentId != null) {
            for (LuckgramAgents.Agent a : LuckgramAgents.custom(currentAccount)) {
                if (a.id.equals(agentId)) agent = a;
            }
        }
        if (agent == null) agent = LuckgramAgents.newCustom();
        color = agent.color;
        return super.onFragmentCreate();
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(agentId == null ? LuckgramStrings.get("创建智能体", "New agent") : LuckgramStrings.get("编辑智能体", "Edit agent"));
        actionBar.createMenu().addItem(MENU_DONE, R.drawable.ic_ab_done).setContentDescription(LuckgramStrings.get("保存", "Save"));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                } else if (id == MENU_DONE) {
                    save();
                }
            }
        });

        ScrollView scroll = new ScrollView(context);
        scroll.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));
        fragmentView = scroll;
        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(20), dp(12), dp(20), dp(24));
        scroll.addView(column);

        column.addView(label(context, LuckgramStrings.get("名称", "Name")));
        nameField = field(context, LuckgramStrings.get("例如：只用英文回答的翻译官", "e.g. English-only translator"), false);
        nameField.setFilters(new InputFilter[]{new InputFilter.LengthFilter(LuckgramAgents.MAX_NAME)});
        nameField.setText(agent.name());
        column.addView(nameField, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 4, 0, 16));

        column.addView(label(context, LuckgramStrings.get("简介", "Description")));
        descField = field(context, LuckgramStrings.get("一句话说明它做什么", "One line about what it does"), false);
        descField.setFilters(new InputFilter[]{new InputFilter.LengthFilter(100)});
        descField.setText(agent.description());
        column.addView(descField, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 4, 0, 16));

        column.addView(label(context, LuckgramStrings.get("指令", "Instructions")));
        promptField = field(context, LuckgramStrings.get("告诉它是谁、怎么回答，例如：你是翻译官，无论用户说什么语言，都只用英文回答。", "Who it is and how to answer, e.g. You are a translator. Always answer in English only."), true);
        promptField.setFilters(new InputFilter[]{new InputFilter.LengthFilter(LuckgramAgents.MAX_PROMPT)});
        promptField.setText(agent.prompt);
        column.addView(promptField, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 4, 0, 16));

        column.addView(label(context, LuckgramStrings.get("头像颜色", "Avatar color")));
        colorRow = new LinearLayout(context);
        colorRow.setOrientation(LinearLayout.HORIZONTAL);
        for (int c : LuckgramAgents.COLORS) {
            View dot = new View(context);
            dot.setTag(c);
            dot.setOnClickListener(v -> {
                color = (Integer) v.getTag();
                updateColors();
            });
            colorRow.addView(dot, LayoutHelper.createLinear(32, 32, 0, 6, 10, 6));
        }
        column.addView(colorRow);
        updateColors();

        if (agentId != null) {
            TextView delete = new TextView(context);
            delete.setText(LuckgramStrings.get("删除智能体", "Delete agent"));
            delete.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
            delete.setTextColor(getThemedColor(Theme.key_text_RedRegular));
            delete.setGravity(Gravity.CENTER);
            delete.setPadding(0, dp(12), 0, dp(12));
            delete.setBackground(Theme.createSelectorDrawable(getThemedColor(Theme.key_listSelector), 2));
            delete.setOnClickListener(v -> confirmDelete(this, agent, this::finishFragment));
            column.addView(delete, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 24, 0, 0));
        }
        return fragmentView;
    }

    private TextView label(Context context, String text) {
        TextView t = new TextView(context);
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        t.setTypeface(AndroidUtilities.bold());
        t.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlueHeader));
        return t;
    }

    private EditTextBoldCursor field(Context context, String hint, boolean multiline) {
        EditTextBoldCursor f = new EditTextBoldCursor(context);
        f.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        f.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
        f.setHintTextColor(getThemedColor(Theme.key_windowBackgroundWhiteHintText));
        f.setCursorColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
        f.setHint(hint);
        f.setBackground(Theme.createEditTextDrawable(context, false));
        f.setPadding(0, dp(6), 0, dp(8));
        if (multiline) {
            f.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
            f.setMinLines(4);
            f.setMaxLines(12);
            f.setGravity(Gravity.TOP | Gravity.LEFT);
        } else {
            f.setSingleLine(true);
        }
        return f;
    }

    private void updateColors() {
        for (int i = 0; i < colorRow.getChildCount(); i++) {
            View dot = colorRow.getChildAt(i);
            int c = (Integer) dot.getTag();
            GradientDrawable d = new GradientDrawable();
            d.setShape(GradientDrawable.OVAL);
            d.setColor(c);
            if (c == color) d.setStroke(dp(3), getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
            dot.setBackground(d);
            dot.setContentDescription(c == color ? LuckgramStrings.get("已选", "Selected") : null);
        }
    }

    private void save() {
        String name = nameField.getText().toString().trim();
        String prompt = promptField.getText().toString().trim();
        if (TextUtils.isEmpty(name)) {
            AndroidUtilities.shakeViewSpring(nameField, 4);
            BulletinFactory.of(this).createErrorBulletin(LuckgramStrings.get("请填写名称", "Please enter a name")).show();
            return;
        }
        if (TextUtils.isEmpty(prompt)) {
            AndroidUtilities.shakeViewSpring(promptField, 4);
            BulletinFactory.of(this).createErrorBulletin(LuckgramStrings.get("请填写指令", "Please enter instructions")).show();
            return;
        }
        agent.setCustom(name, descField.getText().toString().trim(), prompt, color);
        LuckgramAgents.save(currentAccount, agent);
        finishFragment();
    }

    static void confirmDelete(BaseFragment fragment, LuckgramAgents.Agent agent, Runnable done) {
        AlertDialog dialog = new AlertDialog.Builder(fragment.getParentActivity(), fragment.getResourceProvider())
            .setTitle(LuckgramStrings.get("删除智能体", "Delete agent"))
            .setMessage(LuckgramStrings.get("「" + agent.name() + "」和它的对话都会删除。", "\"" + agent.name() + "\" and its chat will be deleted."))
            .setPositiveButton(LuckgramStrings.get("删除", "Delete"), (d, w) -> {
                LuckgramAgents.delete(fragment.getCurrentAccount(), agent);
                if (done != null) done.run();
            })
            .setNegativeButton(LuckgramStrings.cancel(), null)
            .create();
        fragment.showDialog(dialog);
        TextView button = (TextView) dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE);
        if (button != null) button.setTextColor(Theme.getColor(Theme.key_text_RedBold, fragment.getResourceProvider()));
    }
}

package id.bagas.lerengoffline;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Small design-system layer: semantic tokens, surfaces, buttons, chips and type styles. */
final class UiKit {
    static final int STYLE_PRIMARY = 0;
    static final int STYLE_SECONDARY = 1;
    static final int STYLE_GHOST = 2;
    static final int STYLE_DANGER = 3;
    static final int STYLE_ACCENT = 4;

    static final int TONE_NEUTRAL = 0;
    static final int TONE_SUCCESS = 1;
    static final int TONE_WARNING = 2;
    static final int TONE_ACCENT = 3;
    static final int TONE_DANGER = 4;
    static final int TONE_PRIMARY = 5;

    private static final Typeface MEDIUM = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    private static final Typeface BOLD = Typeface.create("sans-serif", Typeface.BOLD);
    private static final Typeface REGULAR = Typeface.create("sans-serif", Typeface.NORMAL);

    private UiKit() {}

    static int dp(Context context, float value) {
        return (int) (value * context.getResources().getDisplayMetrics().density + 0.5f);
    }

    static int color(Context context, int colorRes) {
        return context.getColor(colorRes);
    }

    static LinearLayout.LayoutParams lp(int width, int height, float left, float top, float right, float bottom) {
        return lp(null, width, height, left, top, right, bottom);
    }

    static LinearLayout.LayoutParams lp(Context context, int width, int height,
                                       float left, float top, float right, float bottom) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(width, height);
        if (context != null)
            params.setMargins(dp(context, left), dp(context, top), dp(context, right), dp(context, bottom));
        return params;
    }

    // ---------------------------------------------------------------- typography

    static TextView text(Context context, CharSequence value, float sizeSp, int colorRes, int style) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(sizeSp);
        view.setTextColor(color(context, colorRes));
        view.setTypeface(style == Typeface.BOLD ? BOLD : style == Typeface.BOLD_ITALIC ? MEDIUM : REGULAR);
        view.setIncludeFontPadding(false);
        return view;
    }

    static TextView heading(Context context, CharSequence value) {
        TextView view = text(context, value, 17, R.color.text, Typeface.BOLD);
        return view;
    }

    static TextView sectionLabel(Context context, CharSequence value) {
        TextView view = text(context, value, 11, R.color.primary, Typeface.BOLD);
        view.setAllCaps(true);
        view.setLetterSpacing(0.09f);
        return view;
    }

    static TextView body(Context context, CharSequence value) {
        TextView view = text(context, value, 13.5f, R.color.text_muted, Typeface.NORMAL);
        view.setLineSpacing(dp(context, 3), 1f);
        return view;
    }

    static TextView caption(Context context, CharSequence value) {
        TextView view = text(context, value, 11.5f, R.color.text_faint, Typeface.NORMAL);
        view.setLineSpacing(dp(context, 2), 1f);
        return view;
    }

    static TextView mono(Context context, CharSequence value) {
        TextView view = text(context, value, 12, R.color.text_muted, Typeface.NORMAL);
        view.setTypeface(Typeface.MONOSPACE);
        view.setLineSpacing(dp(context, 3), 1f);
        view.setFontFeatureSettings("tnum");
        return view;
    }

    // ---------------------------------------------------------------- shapes

    static GradientDrawable fill(Context context, int colorRes, float radiusDp) {
        return fillColor(color(context, colorRes), radiusDp, context);
    }

    static GradientDrawable fillColor(int color, float radiusDp, Context context) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(context, radiusDp));
        return drawable;
    }

    static GradientDrawable strokeFill(Context context, int fillColorRes, int strokeColorRes, float radiusDp) {
        GradientDrawable drawable = fill(context, fillColorRes, radiusDp);
        drawable.setStroke(dp(context, 1), color(context, strokeColorRes));
        return drawable;
    }

    static Drawable ripple(Context context, Drawable content, float radiusDp) {
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(Color.WHITE);
        mask.setCornerRadius(dp(context, radiusDp));
        return new RippleDrawable(ColorStateList.valueOf(color(context, R.color.ripple)), content, mask);
    }

    static Drawable rippleFill(Context context, int fillColorArgb, float radiusDp, boolean onDark) {
        GradientDrawable content = new GradientDrawable();
        content.setColor(fillColorArgb);
        content.setCornerRadius(dp(context, radiusDp));
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(Color.WHITE);
        mask.setCornerRadius(dp(context, radiusDp));
        int rippleColor = color(context, onDark ? R.color.ripple_on_dark : R.color.ripple);
        return new RippleDrawable(ColorStateList.valueOf(rippleColor), content, mask);
    }

    static GradientDrawable circle(Context context, int colorRes) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.OVAL);
        drawable.setColor(color(context, colorRes));
        return drawable;
    }

    // ---------------------------------------------------------------- surfaces

    static LinearLayout card(Context context) {
        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(context, 16), dp(context, 15), dp(context, 16), dp(context, 16));
        box.setBackground(strokeFill(context, R.color.surface, R.color.outline, 20));
        box.setElevation(dp(context, 1));
        return box;
    }

    static LinearLayout cardHeader(Context context, int iconRes, CharSequence title, CharSequence trailing) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        ImageView icon = new ImageView(context);
        icon.setImageResource(iconRes);
        icon.setColorFilter(color(context, R.color.primary));
        row.addView(icon, new LinearLayout.LayoutParams(dp(context, 19), dp(context, 19)));
        TextView label = text(context, title, 15.5f, R.color.text, Typeface.BOLD);
        label.setPadding(dp(context, 9), 0, dp(context, 8), 0);
        row.addView(label, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        if (trailing != null && trailing.length() > 0) {
            TextView badge = text(context, trailing, 11, R.color.text_faint, Typeface.BOLD);
            badge.setGravity(Gravity.END);
            row.addView(badge);
        }
        return row;
    }

    static View divider(Context context) {
        View divider = new View(context);
        divider.setBackgroundColor(color(context, R.color.outline));
        return divider;
    }

    static void addCard(LinearLayout parent, View card, float topMargin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(parent.getContext(), topMargin);
        parent.addView(card, params);
    }

    // ---------------------------------------------------------------- buttons

    static Button button(Context context, CharSequence label, int style, int iconRes) {
        Button button = new Button(context);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(14);
        button.setTypeface(MEDIUM);
        button.setGravity(Gravity.CENTER);
        button.setMinHeight(dp(context, 48));
        button.setMinimumHeight(dp(context, 48));
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setPadding(dp(context, 14), 0, dp(context, 14), 0);
        button.setIncludeFontPadding(false);
        button.setStateListAnimator(null);
        if (iconRes != 0) {
            button.setCompoundDrawablesRelativeWithIntrinsicBounds(iconRes, 0, 0, 0);
            button.setCompoundDrawablePadding(dp(context, 8));
        }
        styleButton(context, button, style);
        return button;
    }

    static void styleButton(Context context, Button button, int style) {
        int fillRes;
        int disabledFillRes = R.color.neutral_container;
        int textRes;
        int strokeRes = 0;
        switch (style) {
            case STYLE_SECONDARY:
                fillRes = R.color.primary_container;
                textRes = R.color.on_primary_container;
                break;
            case STYLE_GHOST:
                fillRes = R.color.surface;
                textRes = R.color.primary;
                strokeRes = R.color.outline;
                break;
            case STYLE_DANGER:
                fillRes = R.color.danger_container;
                textRes = R.color.danger;
                break;
            case STYLE_ACCENT:
                fillRes = R.color.accent_container;
                textRes = R.color.on_accent_container;
                break;
            case STYLE_PRIMARY:
            default:
                fillRes = R.color.primary;
                textRes = R.color.on_primary;
                break;
        }
        StateListDrawable content = new StateListDrawable();
        content.addState(new int[]{-android.R.attr.state_enabled},
                strokeRes == 0 ? fill(context, disabledFillRes, 14)
                        : strokeFill(context, disabledFillRes, R.color.outline, 14));
        content.addState(new int[]{},
                strokeRes == 0 ? fill(context, fillRes, 14)
                        : strokeFill(context, fillRes, strokeRes, 14));
        button.setBackground(ripple(context, content, 14));
        ColorStateList textColors = new ColorStateList(
                new int[][]{new int[]{-android.R.attr.state_enabled}, new int[]{}},
                new int[]{color(context, R.color.text_faint), color(context, textRes)});
        button.setTextColor(textColors);
        for (Drawable drawable : button.getCompoundDrawablesRelative()) {
            if (drawable != null) drawable.setTintList(textColors);
        }
    }

    static ImageView iconButton(Context context, int iconRes, CharSequence contentDescription, int tintRes) {
        ImageView view = new ImageView(context);
        view.setImageResource(iconRes);
        view.setColorFilter(color(context, tintRes));
        view.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        view.setPadding(dp(context, 12), dp(context, 12), dp(context, 12), dp(context, 12));
        view.setBackground(rippleFill(context, Color.TRANSPARENT, 24, false));
        view.setContentDescription(contentDescription);
        view.setClickable(true);
        view.setFocusable(true);
        view.setMinimumWidth(dp(context, 48));
        view.setMinimumHeight(dp(context, 48));
        return view;
    }

    static EditText input(Context context, CharSequence hint) {
        EditText edit = new EditText(context);
        edit.setTextSize(15);
        edit.setTextColor(color(context, R.color.text));
        edit.setHintTextColor(color(context, R.color.text_faint));
        edit.setHint(hint);
        edit.setSingleLine(true);
        edit.setInputType(InputType.TYPE_CLASS_TEXT);
        edit.setBackground(strokeFill(context, R.color.surface_alt, R.color.outline, 12));
        edit.setPadding(dp(context, 14), dp(context, 12), dp(context, 14), dp(context, 12));
        edit.setMinHeight(dp(context, 48));
        return edit;
    }

    // ---------------------------------------------------------------- chips & stats

    static final class Chip {
        final Context context;
        final LinearLayout root;
        final ImageView icon;
        final TextView label;

        Chip(Context context) {
            this.context = context;
            root = new LinearLayout(context);
            root.setOrientation(LinearLayout.HORIZONTAL);
            root.setGravity(Gravity.CENTER_VERTICAL);
            root.setPadding(dp(context, 10), 0, dp(context, 10), 0);
            root.setMinimumHeight(dp(context, 30));
            icon = new ImageView(context);
            icon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            root.addView(icon, new LinearLayout.LayoutParams(dp(context, 14), dp(context, 14)));
            label = text(context, "", 11.5f, R.color.text_muted, Typeface.BOLD);
            label.setSingleLine(true);
            label.setEllipsize(android.text.TextUtils.TruncateAt.END);
            label.setPadding(dp(context, 6), 0, 0, 0);
            root.addView(label);
        }

        void set(int tone, String text) {
            label.setText(text);
            int bgRes;
            int fgRes;
            switch (tone) {
                case TONE_SUCCESS:
                    bgRes = R.color.success_container; fgRes = R.color.success; break;
                case TONE_WARNING:
                    bgRes = R.color.warning_container; fgRes = R.color.warning; break;
                case TONE_ACCENT:
                    bgRes = R.color.accent_container; fgRes = R.color.on_accent_container; break;
                case TONE_DANGER:
                    bgRes = R.color.danger_container; fgRes = R.color.danger; break;
                case TONE_PRIMARY:
                    bgRes = R.color.primary_container; fgRes = R.color.on_primary_container; break;
                case TONE_NEUTRAL:
                default:
                    bgRes = R.color.neutral_container; fgRes = R.color.text_muted; break;
            }
            root.setBackground(fill(context, bgRes, 15));
            label.setTextColor(color(context, fgRes));
            icon.setColorFilter(color(context, fgRes));
        }
    }

    static final class Stat {
        final LinearLayout root;
        final TextView value;
        final TextView label;

        Stat(Context context, String labelText) {
            root = new LinearLayout(context);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setPadding(dp(context, 12), dp(context, 11), dp(context, 12), dp(context, 11));
            root.setBackground(fill(context, R.color.surface_alt, 14));
            value = text(context, "—", 20, R.color.text, Typeface.BOLD);
            value.setFontFeatureSettings("tnum");
            value.setSingleLine(true);
            label = text(context, labelText, 10.5f, R.color.text_faint, Typeface.BOLD);
            label.setAllCaps(true);
            label.setLetterSpacing(0.06f);
            label.setPadding(0, dp(context, 3), 0, 0);
            root.addView(value);
            root.addView(label);
        }

        void set(String text) {
            value.setText(text);
        }
    }

    static LinearLayout statRow(Context context, Stat left, Stat right, float gap) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams first = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        first.rightMargin = dp(context, gap);
        row.addView(left.root, first);
        row.addView(right.root, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        return row;
    }
}

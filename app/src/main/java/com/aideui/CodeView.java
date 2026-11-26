package com.aideui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.text.Editable;
import android.text.Layout;
import android.text.TextWatcher;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.widget.EditText;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/** EditText that draws red wavy underlines under error ranges and keeps a lightweight undo history. */
public class CodeView extends EditText {
    private List<JavaAnalyzer.Err> errors = new ArrayList<JavaAnalyzer.Err>();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final float d;
    private final IndentGuides blockGuides = new IndentGuides();
    private int textVersion = 0;

    /** AIDE-style indentation guides and active block/tag highlights. */
    public void configureGuides(boolean enabled, boolean tagMode, int spaces) {
        if (blockGuides.configure(enabled, tagMode, false, spaces)) invalidate();
    }

    @Override
    protected void onTextChanged(CharSequence text, int start, int before, int count) {
        super.onTextChanged(text, start, before, count);
        textVersion++;
    }

    private int lastSelectedStart = -1, lastSelectedEnd = -1;
    private int lastCursor = -1;
    private boolean restoringHistory = false;
    private CharSequence beforeChangeText = "";
    private int beforeSelStart = 0, beforeSelEnd = 0;
    private final ArrayDeque<State> undo = new ArrayDeque<State>();
    private final ArrayDeque<State> redo = new ArrayDeque<State>();
    private static final int MAX_HISTORY = 120;

    static class State {
        final String text;
        final int selStart, selEnd;
        State(String t, int s, int e) { text = t; selStart = s; selEnd = e; }
    }

    public CodeView(Context c, AttributeSet a) {
        super(c, a);
        d = c.getResources().getDisplayMetrics().density;
        paint.setColor(Color.parseColor("#E00000"));
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.5f * d);
        initHistory();
    }

    void initHistory() {
        addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                if (restoringHistory) return;
                beforeChangeText = s.toString();
                beforeSelStart = Math.max(0, getSelectionStart());
                beforeSelEnd = Math.max(0, getSelectionEnd());
            }
            public void onTextChanged(CharSequence s, int start, int before, int count) { }
            public void afterTextChanged(Editable s) {
                if (restoringHistory) return;
                String now = s.toString();
                String old = beforeChangeText == null ? "" : beforeChangeText.toString();
                if (!now.equals(old)) {
                    pushUndo(new State(old, beforeSelStart, beforeSelEnd));
                    redo.clear();
                }
            }
        });
    }

    void pushUndo(State state) {
        if (state == null) return;
        if (!undo.isEmpty() && undo.peekLast().text.equals(state.text)
                && undo.peekLast().selStart == state.selStart && undo.peekLast().selEnd == state.selEnd) return;
        while (undo.size() >= MAX_HISTORY) undo.removeFirst();
        undo.addLast(state);
    }

    State captureCurrent() {
        return new State(getText() == null ? "" : getText().toString(),
                Math.max(0, getSelectionStart()), Math.max(0, getSelectionEnd()));
    }

    void applyState(State state) {
        restoringHistory = true;
        try {
            setText(state.text);
            int len = length();
            int s = Math.max(0, Math.min(state.selStart, len));
            int e = Math.max(0, Math.min(state.selEnd, len));
            setSelection(s, e);
        } finally {
            restoringHistory = false;
        }
    }

    public void clearHistoryToCurrent() {
        undo.clear();
        redo.clear();
    }

    public boolean canUndo() { return !undo.isEmpty(); }
    public boolean canRedo() { return !redo.isEmpty(); }

    public boolean undo() {
        if (undo.isEmpty()) return false;
        redo.addLast(captureCurrent());
        applyState(undo.removeLast());
        return true;
    }

    public boolean redo() {
        if (redo.isEmpty()) return false;
        undo.addLast(captureCurrent());
        applyState(redo.removeLast());
        return true;
    }

    @Override
    protected void onSelectionChanged(int start, int end) {
        super.onSelectionChanged(start, end);
        // Active block follows the caret, even when no code is inserted.
        invalidate();
        if (end >= 0) lastCursor = end;
        if (start >= 0 && end > start) {
            lastSelectedStart = start;
            lastSelectedEnd = end;
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) forgetRefactorSelection();
        return super.onTouchEvent(event);
    }

    public void forgetRefactorSelection() {
        lastSelectedStart = -1;
        lastSelectedEnd = -1;
        lastCursor = -1;
    }

    public int codeCaret() {
        int value = getSelectionStart();
        return value >= 0 ? value : lastCursor;
    }

    public int codeEnd() {
        int value = getSelectionEnd();
        return value >= 0 ? value : lastCursor;
    }

    public int refactorStart() {
        int start = getSelectionStart(), end = getSelectionEnd();
        return start >= 0 && end > start ? start : lastSelectedStart;
    }

    public int refactorEnd() {
        int start = getSelectionStart(), end = getSelectionEnd();
        return start >= 0 && end > start ? end : lastSelectedEnd;
    }

    public void setErrors(List<JavaAnalyzer.Err> e) {
        errors = e == null ? new ArrayList<JavaAnalyzer.Err>() : e;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        // Draw over the text background, but inside indentation whitespace.
        blockGuides.draw(canvas, this, textVersion, d);
        Layout l = getLayout();
        int len = getText().length();
        if (l == null || errors.isEmpty() || len == 0) return;
        canvas.save();
        canvas.translate(getCompoundPaddingLeft(), getExtendedPaddingTop());
        for (JavaAnalyzer.Err e : errors) {
            paint.setColor(e.warning ? Color.parseColor("#F5B400") : Color.parseColor("#E00000"));
            int s = Math.min(e.start, len - 1);
            int en = Math.min(Math.max(e.end, s + 1), len);
            int l1 = l.getLineForOffset(s), l2 = l.getLineForOffset(en - 1);
            for (int line = l1; line <= l2; line++) {
                int ls = Math.max(s, l.getLineStart(line));
                int le = Math.min(en, l.getLineVisibleEnd(line));
                if (le <= ls) continue;
                float x1 = l.getPrimaryHorizontal(ls), x2 = l.getPrimaryHorizontal(le);
                float y = l.getLineBaseline(line) + 2.5f * d;
                float step = 3f * d, amp = 1.6f * d;
                path.reset();
                path.moveTo(x1, y);
                boolean up = true;
                for (float x = x1 + step; x < x2 + step; x += step) {
                    path.lineTo(Math.min(x, x2), y + (up ? -amp : amp));
                    up = !up;
                }
                canvas.drawPath(path, paint);
            }
        }
        canvas.restore();
    }
}

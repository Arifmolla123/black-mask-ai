package com.blackmask.jarvis;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

/**
 * Memory page (v4.9.43): everything the app remembers ("remember my
 * bike number is ..."), on its own page so a bug here cannot touch
 * the main screens. View it, add one, delete one, or delete them all.
 * Voice still works the same: "remember my bike number is WB12AB3456"
 * saves, "what is my bike number" / "আমার বাইকের নম্বর কি" recalls.
 */
public class MemoryActivity extends Activity {

    private LinearLayout rows;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_memory);
        rows = findViewById(R.id.memoryRows);
        Button addBtn = findViewById(R.id.memoryAddBtn);
        addBtn.setOnClickListener(v -> showAddDialog());
        Button clearBtn = findViewById(R.id.memoryClearBtn);
        clearBtn.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle(R.string.memory_confirm_title)
                .setMessage(R.string.memory_confirm_msg)
                .setPositiveButton(R.string.memory_delete_all, (d, w) -> {
                    CommandEngine.clearMemories(this);
                    refresh();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show());
        refresh();
    }

    private void refresh() {
        rows.removeAllViews();
        List<String[]> all = CommandEngine.listMemories(this);
        if (all.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText(R.string.memory_empty);
            empty.setTextColor(0xff9aa5b1);
            empty.setTextSize(14);
            empty.setPadding(4, 8, 4, 8);
            rows.addView(empty);
            return;
        }
        for (String[] kv : all) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(4, 8, 4, 8);
            TextView tv = new TextView(this);
            tv.setText(kv[0] + ": " + kv[1]);
            tv.setTextColor(0xffe8ecf1);
            tv.setTextSize(14);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            tv.setLayoutParams(lp);
            Button del = new Button(this);
            del.setText("✕");
            del.setTextColor(0xffff2a3c);
            del.setBackgroundResource(R.drawable.btn_dark);
            final String key = kv[0];
            del.setOnClickListener(v -> {
                CommandEngine.deleteMemory(this, key);
                refresh();
            });
            row.addView(tv);
            row.addView(del);
            rows.addView(row);
        }
    }

    private void showAddDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(32, 16, 32, 16);
        final EditText keyEt = new EditText(this);
        keyEt.setHint(R.string.memory_key);
        final EditText valEt = new EditText(this);
        valEt.setHint(R.string.memory_value);
        box.addView(keyEt);
        box.addView(valEt);
        new AlertDialog.Builder(this)
                .setTitle(R.string.memory_add)
                .setView(box)
                .setPositiveButton(R.string.memory_add, (d, w) -> {
                    String k = keyEt.getText().toString().trim();
                    String v = valEt.getText().toString().trim();
                    if (!k.isEmpty() && !v.isEmpty()) {
                        CommandEngine.addMemory(this, k, v);
                        refresh();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }
}

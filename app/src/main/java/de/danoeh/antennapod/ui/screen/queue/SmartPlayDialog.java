package de.danoeh.antennapod.ui.screen.queue;

import android.content.Context;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import de.danoeh.antennapod.model.feed.Feed;
import de.danoeh.antennapod.storage.database.DBReader;
import de.danoeh.antennapod.storage.database.SmartPlayManager;
import de.danoeh.antennapod.storage.database.SmartPlayManager.SmartPlayRule;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.schedulers.Schedulers;

public class SmartPlayDialog {

    private static class SourceOption {
        final String label;
        final String type;
        final String value;

        SourceOption(String label, String type, String value) {
            this.label = label;
            this.type = type;
            this.value = value;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    public static void show(Context context) {
        Single.fromCallable(() -> {
                    List<Feed> feeds = DBReader.getFeedList();
                    Set<String> tags = new HashSet<>();
                    List<Feed> subscribedFeeds = new ArrayList<>();
                    for (Feed f : feeds) {
                        if (f.getState() == Feed.STATE_SUBSCRIBED) {
                            subscribedFeeds.add(f);
                            if (f.getPreferences() != null && f.getPreferences().getTags() != null) {
                                for (String t : f.getPreferences().getTags()) {
                                    if (!"#root".equals(t)) {

                                        tags.add(t);
                                    }
                                }
                            }
                        }
                    }
                    List<String> sortedTags = new ArrayList<>(tags);
                    Collections.sort(sortedTags);
                    Collections.sort(subscribedFeeds, (a, b) -> a.getTitle().compareToIgnoreCase(b.getTitle()));

                    List<SourceOption> options = new ArrayList<>();
                    options.add(new SourceOption("Category: All feeds", "ALL", ""));
                    for (String tag : sortedTags) {
                        options.add(new SourceOption("Category: " + tag, "TAG", tag));
                    }
                    for (Feed f : subscribedFeeds) {
                        options.add(new SourceOption(f.getTitle(), "FEED", String.valueOf(f.getId())));
                    }
                    return options;
                })
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(sourceOptions -> showDialogWithSources(context, sourceOptions),
                        err -> Toast.makeText(context, "Error loading feeds", Toast.LENGTH_SHORT).show());
    }


    private static void showDialogWithSources(Context context, List<SourceOption> sourceOptions) {
        float dp = context.getResources().getDisplayMetrics().density;
        int pad = (int) (16 * dp);

        ScrollView scrollView = new ScrollView(context);
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        scrollView.addView(root);

        List<SmartPlayRule> rules = SmartPlayManager.getRules(context);
        if (rules.isEmpty()) {
            rules.add(new SmartPlayRule("OLDEST", 1, "ALL", ""));
        }

        LinearLayout rulesContainer = new LinearLayout(context);
        rulesContainer.setOrientation(LinearLayout.VERTICAL);
        root.addView(rulesContainer);

        Runnable[] rebuildRuleViews = new Runnable[1];
        rebuildRuleViews[0] = () -> {
            rulesContainer.removeAllViews();
            for (int i = 0; i < rules.size(); i++) {
                final int index = i;
                SmartPlayRule rule = rules.get(i);

                LinearLayout card = new LinearLayout(context);
                card.setOrientation(LinearLayout.VERTICAL);
                card.setPadding((int) (8 * dp), (int) (8 * dp), (int) (8 * dp), (int) (12 * dp));

                LinearLayout headerRow = new LinearLayout(context);
                headerRow.setOrientation(LinearLayout.HORIZONTAL);
                headerRow.setGravity(Gravity.CENTER_VERTICAL);

                TextView header = new TextView(context);
                header.setText(i == 0 ? "START WITH" : "THEN PLAY");
                header.setTypeface(null, Typeface.BOLD);
                header.setTextSize(12);
                LinearLayout.LayoutParams headerParams = new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                headerRow.addView(header, headerParams);

                Button removeBtn = new Button(context, null, android.R.attr.borderlessButtonStyle);
                removeBtn.setText("✕");
                removeBtn.setOnClickListener(v -> {
                    rules.remove(index);
                    rebuildRuleViews[0].run();
                });
                headerRow.addView(removeBtn);
                card.addView(headerRow);
                String[] orderLabels = {"Oldest", "Newest", "Random"};
                String[] orderCodes = {"OLDEST", "NEWEST", "RANDOM"};
                Spinner orderSpinner = new Spinner(context);
                orderSpinner.setAdapter(new ArrayAdapter<>(context,
                        android.R.layout.simple_spinner_dropdown_item, orderLabels));
                for (int o = 0; o < orderCodes.length; o++) {
                    if (orderCodes[o].equals(rule.order)) {
                        orderSpinner.setSelection(o);
                    }
                }
                orderSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
                    @Override
                    public void onItemSelected(android.widget.AdapterView<?> p, android.view.View v, int pos, long id) {
                        rule.order = orderCodes[pos];
                    }
                    @Override
                    public void onNothingSelected(android.widget.AdapterView<?> p) {}
                });
                card.addView(orderSpinner);

                String[] countLabels = {"1 episode", "2 episodes", "3 episodes", "5 episodes", "10 episodes", "All episodes"};
                int[] countValues = {1, 2, 3, 5, 10, -1};
                Spinner countSpinner = new Spinner(context);
                countSpinner.setAdapter(new ArrayAdapter<>(context,
                        android.R.layout.simple_spinner_dropdown_item, countLabels));
                for (int c = 0; c < countValues.length; c++) {
                    if (countValues[c] == rule.count) {
                        countSpinner.setSelection(c);
                    }
                }
                countSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
                    @Override
                    public void onItemSelected(android.widget.AdapterView<?> p, android.view.View v, int pos, long id) {
                        rule.count = countValues[pos];
                    }
                    @Override
                    public void onNothingSelected(android.widget.AdapterView<?> p) {}
                });
                card.addView(countSpinner);

                Spinner sourceSpinner = new Spinner(context);
                sourceSpinner.setAdapter(new ArrayAdapter<>(context,
                        android.R.layout.simple_spinner_dropdown_item, sourceOptions));
                for (int s = 0; s < sourceOptions.size(); s++) {
                    SourceOption opt = sourceOptions.get(s);
                    if (opt.type.equals(rule.sourceType) && opt.value.equals(rule.sourceValue)) {
                        sourceSpinner.setSelection(s);
                    }
                }
                sourceSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
                    @Override
                    public void onItemSelected(android.widget.AdapterView<?> p, android.view.View v, int pos, long id) {
                        SourceOption selected = sourceOptions.get(pos);
                        rule.sourceType = selected.type;
                        rule.sourceValue = selected.value;
                    }
                    @Override
                    public void onNothingSelected(android.widget.AdapterView<?> p) {}
                });
                card.addView(sourceSpinner);

                rulesContainer.addView(card);
            }
        };
        rebuildRuleViews[0].run();

        Button addRuleBtn = new Button(context, null, android.R.attr.borderlessButtonStyle);
        addRuleBtn.setText("+ ADD RULE");
        addRuleBtn.setOnClickListener(v -> {
            rules.add(new SmartPlayRule("OLDEST", 1, "ALL", ""));
            rebuildRuleViews[0].run();
        });
        root.addView(addRuleBtn);

        CheckBox autoRebuildBox = new CheckBox(context);
        autoRebuildBox.setText("Auto Rebuild SmartPlay when new episodes download or sync");
        autoRebuildBox.setChecked(SmartPlayManager.isAutoRebuildEnabled(context));
        root.addView(autoRebuildBox);

        CheckBox keepCurrentBox = new CheckBox(context);
        keepCurrentBox.setText("Keep Current Episode at the top of the playlist");
        keepCurrentBox.setChecked(SmartPlayManager.isKeepCurrentEnabled(context));
        root.addView(keepCurrentBox);

        CheckBox downloadedOnlyBox = new CheckBox(context);
        downloadedOnlyBox.setText("Only include downloaded or local folder episodes");
        downloadedOnlyBox.setChecked(SmartPlayManager.isDownloadedOnlyEnabled(context));
        root.addView(downloadedOnlyBox);

        new MaterialAlertDialogBuilder(context)
                .setTitle("SmartPlay Builder")
                .setView(scrollView)
                .setPositiveButton("Save & Build Now", (dialog, which) -> {
                    SmartPlayManager.saveConfig(context, rules,
                            autoRebuildBox.isChecked(), keepCurrentBox.isChecked(), downloadedOnlyBox.isChecked());
                    SmartPlayManager.rebuildQueueNow(context);
                    Toast.makeText(context, "SmartPlay queue built!", Toast.LENGTH_SHORT).show();
                })
                .setNeutralButton("Save Only", (dialog, which) -> {
                    SmartPlayManager.saveConfig(context, rules,
                            autoRebuildBox.isChecked(), keepCurrentBox.isChecked(), downloadedOnlyBox.isChecked());
                })
                .setNegativeButton("Cancel", null)
                .show();

    }
}

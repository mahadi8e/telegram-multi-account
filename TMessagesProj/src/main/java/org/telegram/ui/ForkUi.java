/*
 * TG fork: small shared UI helpers for the fork's own screens.
 */

package org.telegram.ui;

import android.graphics.Rect;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.Components.RecyclerListView;

public class ForkUi {

    // TG fork: extra breathing room above the first row of a fork list
    // screen. On the stock glass screens the floating header's blur/fade
    // reaches below the pill header, and a section card in row 0 sits
    // right at the stock content top (insets + action bar height), so
    // its top edge and first row are washed over by the header — the
    // stock setSections(true) first-row offset is only 12dp (vs 4dp),
    // which barely moves the card. The fork adds a decisive gap of its
    // own; the section background is drawn from the first child's top,
    // so the card moves down together with its content.
    public static void addFirstRowTopGap(RecyclerListView listView) {
        listView.addItemDecoration(new RecyclerView.ItemDecoration() {
            @Override
            public void getItemOffsets(@NonNull Rect outRect, @NonNull View view, @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
                if (parent.getChildAdapterPosition(view) == 0) {
                    outRect.top += AndroidUtilities.dp(28);
                }
            }
        });
    }
}

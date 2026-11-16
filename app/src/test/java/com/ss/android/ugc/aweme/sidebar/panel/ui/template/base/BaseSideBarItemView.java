package com.ss.android.ugc.aweme.sidebar.panel.ui.template.base;

import android.content.Context;
import android.widget.FrameLayout;
import android.widget.TextView;

/** Native item identity fixture: the title is display text, never its business identity. */
public final class BaseSideBarItemView extends FrameLayout {
    public final ItemContext a;
    public BaseSideBarItemView(Context context,String id,String title) {
        super(context);a=new ItemContext(id);TextView text=new TextView(context);text.setText(title);addView(text);
    }
    public static final class ItemContext {
        private final String id;
        ItemContext(String id){this.id=id;}
        public String getBusinessId(){return id;}
    }
}

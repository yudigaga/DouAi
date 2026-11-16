package com.ss.android.ugc.aweme.homepage.tab.ui.bottom;
import android.content.Context;
import android.widget.FrameLayout;
import android.widget.TextView;
/** Public host contract fixture, with ordinary Android views and no obfuscated class. */
public class BottomTab extends FrameLayout {
    private final String type;
    private final TextView title;
    public BottomTab(Context context,String type,String text) {
        super(context);this.type=type;title=new TextView(context);title.setText(text);addView(title);
    }
    public String getTabType(){return type;}
    public TextView getTabTitle(){return title;}

    /** Native selection need not propagate into the title's Android drawable state. */
    public static final class OwnerSelectedTab extends BottomTab {
        private boolean selected;
        public OwnerSelectedTab(Context context){super(context,"HOME","首页");}
        @Override public void setSelected(boolean selected){this.selected=selected;}
        @Override public boolean isSelected(){return selected;}
    }
}

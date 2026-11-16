package com.ss.android.ugc.aweme.homepage.tab.top.ui.tabstrip.item;
import android.content.Context;
import android.view.View;
import android.widget.FrameLayout;
/** getContentView can legitimately expose an animation instead of a label. */
public class HomeTopTabItemViewNew extends FrameLayout {
    private final String id;
    private final View content;
    public int idReads;
    public HomeTopTabItemViewNew(Context context,String id,View content) {
        super(context);this.id=id;this.content=content;addView(content);
    }
    public String getCurrentTabId(){idReads++;return id;}
    public View getContentView(){return content;}

    /** Uses the host namespace so discovery reaches the verified delegate contract. */
    public static final class NativeThemeTop extends HomeTopTabItemViewNew {
        public final NativeThemeDelegate r=new NativeThemeDelegate();
        public int delegateReads;
        public NativeThemeTop(Context context,View content){this(context,"homepage_hot",content);}
        public NativeThemeTop(Context context,String id,View content){super(context,id,content);}
        public NativeThemeDelegate getTabViewDelegate(){delegateReads++;return r;}
    }
    public static final class NativeThemeDelegate {
        public final NativeThemeProvider A=new NativeThemeProvider();
        public boolean selected,selectionUnavailable;
        public int selectionReads;
        public boolean LJIIZILJ(){selectionReads++;if(selectionUnavailable)throw new IllegalStateException("host selection unavailable");return selected;}
    }
    public static final class NativeThemeProvider {
        public boolean dark,unavailable;
        public int themeReads;
        public boolean isDarkMode(){themeReads++;if(unavailable)throw new IllegalStateException("host theme unavailable");return dark;}
    }
}

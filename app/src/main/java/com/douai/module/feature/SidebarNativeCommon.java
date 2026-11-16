package com.douai.module.feature;

import android.view.View;
import android.view.ViewGroup;
import android.widget.GridLayout;
import android.widget.TextView;
import com.douai.module.config.SidebarSettings;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/** Only filters stable business IDs inside an already verified native common section. */
final class SidebarNativeCommon {
    private static final HostReflectionCache ACCESS=new HostReflectionCache();
    private final Map<View,Item> items=new IdentityHashMap<>();

    void update(ViewGroup root,boolean enabled,String selection) {
        List<View> found=new ArrayList<>();collect(root,found,new int[]{512},0);
        if(!enabled) {
            restore();List<SidebarSettings.Entry> catalog=new ArrayList<>();
            for(View view:found){SidebarSettings.Entry entry=entry(view);if(entry!=null)catalog.add(entry);}
            SidebarSettings.rememberCommonEntries(catalog);return;
        }
        for(View old:new ArrayList<>(items.keySet()))if(!found.contains(old)){items.remove(old).restore();}
        List<SidebarSettings.Entry> catalog=new ArrayList<>();
        for(View view:found) {
            SidebarSettings.Entry entry=entry(view);
            if(entry==null){Item old=items.remove(view);if(old!=null)old.restore();continue;}
            catalog.add(entry);Item item=items.get(view);
            if(item==null || !item.id.equals(entry.id)){if(item!=null)item.restore();item=new Item(view,entry.id);items.put(view,item);}
            item.hide(!SidebarSettings.commonVisible(selection,entry.id));
        }
        SidebarSettings.rememberCommonEntries(catalog);
        java.util.Set<GridLayout> grids=java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        for(View view:found)if(view.getParent() instanceof GridLayout)grids.add((GridLayout)view.getParent());
        for(GridLayout grid:grids)compact(grid);
    }
    void restore(){for(Item item:items.values())item.restore();items.clear();}

    private void compact(GridLayout grid) {
        List<Item> children=new ArrayList<>();
        for(int i=0;i<grid.getChildCount();i++) {
            Item item=items.get(grid.getChildAt(i));
            if(item==null || !(item.view.getLayoutParams() instanceof GridLayout.LayoutParams))return;
            item.captureGrid();children.add(item);
            SpecState row=SpecState.read(item.originalRow),column=SpecState.read(item.originalColumn);
            if(row==null || column==null || row.end-row.start!=1 || column.end-column.start!=1) {
                for(Item known:items.values())if(known.view.getParent()==grid)known.restoreGrid();return;
            }
        }
        int slot=0;
        for(Item item:children) {
            if(item.view.getVisibility()!=View.VISIBLE){item.restoreGrid();continue;}
            Item target=children.get(slot++);item.grid(target.originalRow,target.originalColumn);
        }
    }
    private static void collect(View view,List<View> found,int[] remaining,int depth) {
        if(view==null || remaining[0]--<=0 || depth>24)return;
        if(LiquidGlassTargets.hasClass(view,"com.ss.android.ugc.aweme.sidebar.panel.ui.template.base.BaseSideBarItemView")) {
            found.add(view);return;
        }
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)collect(((ViewGroup)view).getChildAt(i),found,remaining,depth+1);
    }
    private static SidebarSettings.Entry entry(View view) {
        try {
            Field context=ACCESS.field(view.getClass(),"a");if(context==null)return null;
            Object item=context.get(view);if(item==null)return null;
            Method id=ACCESS.publicNoArgMethod(item.getClass(),"getBusinessId");if(id==null)return null;
            Object business=id.invoke(item);if(!(business instanceof String))return null;
            String title=null;Field data=ACCESS.field(view.getClass(),"j");
            Object cell=data==null?null:data.get(view);
            if(cell!=null){Method getter=ACCESS.publicNoArgMethod(cell.getClass(),"getTitle");Object value=getter==null?null:getter.invoke(cell);if(value instanceof String)title=(String)value;}
            if(title==null || title.isBlank())title=title(view,new int[]{64});
            return SidebarSettings.commonEntry((String)business,title);
        }catch(ReflectiveOperationException | RuntimeException | LinkageError unavailable){return null;}
    }
    private static String title(View view,int[] remaining) {
        if(remaining[0]--<=0)return null;
        if(view instanceof TextView){CharSequence text=((TextView)view).getText();if(text!=null && !text.toString().isBlank())return text.toString();}
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){String title=title(((ViewGroup)view).getChildAt(i),remaining);if(title!=null)return title;}
        return null;
    }
    private static final class Item {
        final View view;final String id;int originalVisibility;boolean hidden;
        GridLayout.LayoutParams params;GridLayout.Spec originalRow,originalColumn;SpecState lastRow,lastColumn;
        Item(View view,String id){this.view=view;this.id=id;originalVisibility=view.getVisibility();captureGrid();}
        void hide(boolean hide) {
            if(!hide){restoreVisibility();return;}
            if(!hidden || view.getVisibility()!=View.GONE)originalVisibility=view.getVisibility();
            hidden=true;if(view.getVisibility()!=View.GONE)view.setVisibility(View.GONE);
        }
        void restoreVisibility(){if(hidden && view.getVisibility()==View.GONE)view.setVisibility(originalVisibility);hidden=false;}
        void captureGrid() {
            if(!(view.getLayoutParams() instanceof GridLayout.LayoutParams))return;
            GridLayout.LayoutParams live=(GridLayout.LayoutParams)view.getLayoutParams();
            if(params!=live){params=live;originalRow=live.rowSpec;originalColumn=live.columnSpec;lastRow=lastColumn=null;}
            else {if(lastRow==null || !lastRow.matches(live.rowSpec))originalRow=live.rowSpec;if(lastColumn==null || !lastColumn.matches(live.columnSpec))originalColumn=live.columnSpec;}
        }
        void grid(GridLayout.Spec row,GridLayout.Spec column) {
            captureGrid();if(params==null)return;
            SpecState rowSlot=SpecState.read(row),columnSlot=SpecState.read(column);
            SpecState ownRow=SpecState.read(originalRow),ownColumn=SpecState.read(originalColumn);
            if(rowSlot==null || columnSlot==null || ownRow==null || ownColumn==null)return;
            row=ownRow.at(rowSlot.start);column=ownColumn.at(columnSlot.start);
            SpecState nextRow=SpecState.read(row),nextColumn=SpecState.read(column);if(nextRow==null || nextColumn==null)return;
            if(nextRow.matches(params.rowSpec) && nextColumn.matches(params.columnSpec))return;
            params.rowSpec=row;params.columnSpec=column;lastRow=nextRow;lastColumn=nextColumn;view.requestLayout();
        }
        void restoreGrid() {
            if(params==null || view.getLayoutParams()!=params)return;
            boolean changed=false;
            if(lastRow!=null && lastRow.matches(params.rowSpec)){params.rowSpec=originalRow;changed=true;}
            if(lastColumn!=null && lastColumn.matches(params.columnSpec)){params.columnSpec=originalColumn;changed=true;}
            lastRow=lastColumn=null;if(changed)view.requestLayout();
        }
        void restore(){restoreVisibility();restoreGrid();}
    }

    /** GridLayout validates by copying Specs; equals() omits weight and startDefined. */
    private static final class SpecState {
        final int start,end,weight;final boolean defined;final Object alignment;
        SpecState(int start,int end,float weight,boolean defined,Object alignment){this.start=start;this.end=end;this.weight=Float.floatToIntBits(weight);this.defined=defined;this.alignment=alignment;}
        static SpecState read(GridLayout.Spec spec) {
            if(spec==null)return null;
            try {
                Object span=readField(spec,"span");Object align=readField(spec,"alignment");
                return new SpecState(((Number)readField(span,"min")).intValue(),((Number)readField(span,"max")).intValue(),
                        ((Number)readField(spec,"weight")).floatValue(),(Boolean)readField(spec,"startDefined"),align);
            }catch(ReflectiveOperationException | RuntimeException | LinkageError unavailable){return null;}
        }
        boolean matches(GridLayout.Spec spec) {
            SpecState actual=read(spec);return actual!=null && start==actual.start && end==actual.end && weight==actual.weight
                    && defined==actual.defined && alignment==actual.alignment;
        }
        GridLayout.Spec at(int position){return GridLayout.spec(position,end-start,(GridLayout.Alignment)alignment,Float.intBitsToFloat(weight));}
        private static Object readField(Object owner,String name) throws IllegalAccessException,NoSuchFieldException {
            Field field=ACCESS.field(owner.getClass(),name);if(field==null)throw new NoSuchFieldException(name);return field.get(owner);
        }
    }
}

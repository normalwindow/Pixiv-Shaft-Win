package ceui.lisa.view;

import android.graphics.Rect;
import android.view.View;

import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.StaggeredGridLayoutManager;

import ceui.pixiv.ui.desktop.ShaftColumns;

public class SpacesItemDecoration extends RecyclerView.ItemDecoration {

    private final int space;

    public SpacesItemDecoration(int space) {
        this.space = space;
    }

    @Override
    public void getItemOffsets(Rect outRect, View view, RecyclerView parent, RecyclerView.State state) {

        outRect.bottom = space;
        int position = parent.getChildAdapterPosition(view);
        StaggeredGridLayoutManager.LayoutParams params = (StaggeredGridLayoutManager.LayoutParams) view.getLayoutParams();

        int spanCount;
        RecyclerView.LayoutManager lm = parent.getLayoutManager();
        if (lm instanceof StaggeredGridLayoutManager) {
            spanCount = ((StaggeredGridLayoutManager) lm).getSpanCount();
        } else {
            spanCount = ShaftColumns.resolvedFromList(parent);
        }
        if (spanCount < 1) {
            spanCount = 1;
        }

        if (position < spanCount) {
            outRect.top = space;
        }

        int spanIndex = params.getSpanIndex();
        if (spanCount == 1) {
            outRect.left = space;
            outRect.right = space;
        } else if (spanIndex == 0) {
            outRect.left = space;
            outRect.right = space / 2;
        } else if (spanIndex == spanCount - 1) {
            outRect.left = space / 2;
            outRect.right = space;
        } else {
            outRect.left = space / 2;
            outRect.right = space / 2;
        }
    }
}

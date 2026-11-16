package huawei.android.widget;

import android.content.Context;
import android.util.AttributeSet;

/**
 * Compatibility shim for Huawei's framework ListView.
 *
 * MainActivity.jb() stores the menu's ListView in a field declared as
 * huawei.android.widget.ListView.  The Honor inflater creates an AOSP
 * android.widget.ListView, so the module replaces that instance with this
 * subclass before jb() reads it back.
 */
public class ListView extends android.widget.ListView {
    public ListView(Context context) {
        super(context);
    }

    public ListView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public ListView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public ListView(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }
}

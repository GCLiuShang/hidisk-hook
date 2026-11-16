package huawei.android.widget;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.TextView;

/**
 * Compatibility shim for Huawei's framework HwTextView.
 *
 * Honor MagicOS does not ship huawei.android.widget.HwTextView, but hidisk's
 * storage_manage layouts and StorageManagerCardViewLayout reference it
 * directly.  The class only needs TextView behaviour (setText/setVisibility),
 * so subclassing AOSP TextView is sufficient.
 */
public class HwTextView extends TextView {
    public HwTextView(Context context) {
        super(context);
    }

    public HwTextView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public HwTextView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public HwTextView(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }
}

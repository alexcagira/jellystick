package org.jellyfin.androidtv.ui;

import android.content.Context;
import android.graphics.Rect;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.AbsListView;
import android.widget.ImageView;
import android.widget.RelativeLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import org.jellyfin.androidtv.R;
import org.jellyfin.androidtv.ui.livetv.LiveTvGuide;
import org.jellyfin.androidtv.ui.livetv.LiveTvGuideFragment;
import org.jellyfin.androidtv.util.ImageHelper;
import org.jellyfin.androidtv.util.Utils;
import org.jellyfin.sdk.model.api.BaseItemDto;
import org.koin.java.KoinJavaComponent;

public class GuideChannelHeader extends RelativeLayout {
    private AsyncImageView mChannelImage;
    private ImageView mFavImage;
    private BaseItemDto mChannel;
    private Context mContext;
    private LiveTvGuide mTvGuide;
    // The card of the header (rounded, in the colours of the modern screens)
    private View mCell;
    private TextView mChannelName;

    public GuideChannelHeader(Context context, LiveTvGuide tvGuide, BaseItemDto channel) {
        super(context);
        initComponent(context, tvGuide, channel);
    }

    private void initComponent(Context context, LiveTvGuide tvGuide, BaseItemDto channel) {
        mContext = context;
        mChannel = channel;
        mTvGuide = tvGuide;
        LayoutInflater inflater = LayoutInflater.from(context);
        View v = inflater.inflate(R.layout.channel_header, this, false);
        int headerWidth = Utils.convertDpToPixel(context, 160);
        v.setLayoutParams(new AbsListView.LayoutParams(
            headerWidth,
            Utils.convertDpToPixel(context, LiveTvGuideFragment.GUIDE_ROW_HEIGHT_DP)
        ));
        this.addView(v);
        mCell = v;
        this.setFocusable(true);
        mChannelName = findViewById(R.id.channelName);
        mChannelName.setText(channel.getName());
        ((TextView) findViewById(R.id.channelNumber)).setText(channel.getNumber());
        mChannelImage = findViewById(R.id.channelImage);
        mFavImage = findViewById(R.id.favImage);

        if (mChannel.getUserData() != null && mChannel.getUserData().isFavorite())
            mFavImage.setVisibility(View.VISIBLE);
    }

    public void loadImage() {
        ImageHelper imageHelper = KoinJavaComponent.<ImageHelper>get(ImageHelper.class);
        mChannelImage.load(
                imageHelper.getPrimaryImageUrl(mChannel,null, ImageHelper.MAX_PRIMARY_IMAGE_HEIGHT),
                null,
                null,
                0.0,
                0
        );
    }

    public BaseItemDto getChannel() { return mChannel; }
    public void setChannel(BaseItemDto channel) { mChannel = channel; }

    public void refreshFavorite() {
        if (mChannel.getUserData() != null && mChannel.getUserData().isFavorite())
            mFavImage.setVisibility(View.VISIBLE);
        else
            mFavImage.setVisibility(View.GONE);
    }

    @Override
    protected void onFocusChanged(boolean gainFocus, int direction, Rect previouslyFocusedRect) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect);

        // Focused: white with dark text, like the focused buttons of the app
        if (gainFocus) {
            mCell.setBackgroundResource(R.drawable.guide_cell_focused);
            mChannelName.setTextColor(ContextCompat.getColor(mContext, R.color.guide_text_on_focus));

            mTvGuide.setSelectedProgram(this);
        } else {
            mCell.setBackgroundResource(R.drawable.guide_cell_bg);
            mChannelName.setTextColor(ContextCompat.getColor(mContext, R.color.white));
        }
    }
}

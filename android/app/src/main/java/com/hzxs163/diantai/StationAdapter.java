package com.hzxs163.diantai;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageButton;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** 列表适配器：只做绑定，状态（收藏、正在播放）由外面推进来。 */
class StationAdapter extends BaseAdapter {

    private final LayoutInflater inflater;
    private final Set<String> favs;
    private final int colorPrimary;
    private final int colorText;
    private List<Station> items = new ArrayList<>();
    private String playingUrl = "";
    private OnStarClick starClick;

    interface OnStarClick {
        void onStar(Station station);
    }

    void setOnStarClick(OnStarClick l) {
        starClick = l;
    }

    StationAdapter(Context ctx, Set<String> favs) {
        this.inflater = LayoutInflater.from(ctx);
        this.favs = favs;
        this.colorPrimary = ctx.getResources().getColor(R.color.primary, ctx.getTheme());
        this.colorText = ctx.getResources().getColor(R.color.text_primary, ctx.getTheme());
    }

    void setItems(List<Station> list) {
        items = list;
        notifyDataSetChanged();
    }

    void setPlaying(String url) {
        playingUrl = url == null ? "" : url;
        notifyDataSetChanged();
    }

    @Override
    public int getCount() {
        return items.size();
    }

    @Override
    public Station getItem(int position) {
        return items.get(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        View v = convertView;
        if (v == null) {
            v = inflater.inflate(R.layout.item_station, parent, false);
            v.setTag(new Holder(v));
        }
        Holder h = (Holder) v.getTag();
        Station s = items.get(position);
        h.name.setText(s.displayName());
        h.name.setTextColor(s.url.equals(playingUrl) ? colorPrimary : colorText);
        h.meta.setText(s.meta());
        h.star.setImageResource(favs.contains(s.url) ? R.drawable.ic_star_on : R.drawable.ic_star_off);
        final Station row = s;
        h.star.setOnClickListener(v -> {
            if (starClick != null) starClick.onStar(row);
        });
        return v;
    }

    private static final class Holder {
        final TextView name;
        final TextView meta;
        final ImageButton star;

        Holder(View v) {
            name = v.findViewById(R.id.name);
            meta = v.findViewById(R.id.meta);
            star = v.findViewById(R.id.star);
        }
    }
}

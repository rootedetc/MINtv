package local.mintv.details;

import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

import local.mintv.R;
import local.mintv.search.Person;
import local.mintv.ui.ImageLoader;
import local.mintv.ui.Tv;

/** Horizontal cast/crew cards. */
public final class PersonCardAdapter extends RecyclerView.Adapter<PersonCardAdapter.Holder> {

    public interface Listener {
        void onPerson(Person person);
    }

    private final List<Person> people = new ArrayList<>();
    private final Listener listener;

    public PersonCardAdapter(Listener listener) {
        this.listener = listener;
    }

    public void submit(List<Person> next) {
        people.clear();
        if (next != null) {
            people.addAll(next);
        }
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        android.content.Context context = parent.getContext();
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setFocusable(true);
        card.setClickable(true);
        card.setBackground(Tv.states(context, 0x00000000, Tv.SURFACE, Tv.SURFACE_3,
                8f, 0, 0f, 0xFFFFFFFF, 2f));
        Tv.pad(card, 8, 8, 8, 10);
        Tv.focusScale(card, 1.02f);

        ImageView avatar = new ImageView(context);
        avatar.setScaleType(ImageView.ScaleType.CENTER_CROP);
        avatar.setBackgroundResource(R.drawable.bg_avatar);
        avatar.setImageResource(R.drawable.ic_person);
        card.addView(avatar, new LinearLayout.LayoutParams(
                Tv.dp(context, 86), Tv.dp(context, 86)));

        TextView name = Tv.displayText(context, 14f, Tv.TEXT);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        name.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(
                Tv.dp(context, 112), ViewGroup.LayoutParams.WRAP_CONTENT);
        nameLp.topMargin = Tv.dp(context, 6);
        card.addView(name, nameLp);

        TextView role = Tv.text(context, 10.5f, Tv.TEXT_3, false);
        role.setSingleLine(true);
        role.setEllipsize(android.text.TextUtils.TruncateAt.END);
        role.setGravity(Gravity.CENTER);
        card.addView(role, new LinearLayout.LayoutParams(
                Tv.dp(context, 112), ViewGroup.LayoutParams.WRAP_CONTENT));

        return new Holder(card, avatar, name, role);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        Person person = people.get(position);
        holder.name.setText(person.name);
        holder.role.setText(person.roleText());
        ImageLoader.load(holder.avatar, person.imageUrl);
        holder.itemView.setOnClickListener(view -> listener.onPerson(person));
    }

    @Override
    public int getItemCount() {
        return people.size();
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final ImageView avatar;
        final TextView name;
        final TextView role;

        Holder(View itemView, ImageView avatar, TextView name, TextView role) {
            super(itemView);
            this.avatar = avatar;
            this.name = name;
            this.role = role;
        }
    }
}

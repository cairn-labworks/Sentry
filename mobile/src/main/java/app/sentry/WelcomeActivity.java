package app.sentry;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentPagerAdapter;
import androidx.viewpager.widget.ViewPager;

/**
 * A short, swipeable introduction shown the first time the app is opened.
 *
 * <p>Three slides walk through what the app does; the final slide's button
 * records that onboarding is finished and hands off to {@link MainActivity}.
 */
public class WelcomeActivity extends AppCompatActivity {

    private static final int SLIDE_COUNT = 3;

    private ViewPager pager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_welcome);

        pager = findViewById(R.id.container);
        pager.setAdapter(new SlideAdapter(getSupportFragmentManager()));
    }

    /** Advances to the next slide, if there is one. */
    void goToNextSlide(int fromIndex) {
        if (fromIndex + 1 < SLIDE_COUNT) {
            pager.setCurrentItem(fromIndex + 1, true);
        }
    }

    /** Marks onboarding complete and opens the main screen. */
    private void finishOnboarding() {
        SharedPreferences prefs = getSharedPreferences(
                getString(R.string.db_first_launch_complete_flag), Context.MODE_PRIVATE);
        prefs.edit()
                .putString(getString(R.string.db_first_launch_complete_flag), "true")
                .apply();

        startActivity(new Intent(this, MainActivity.class));
        finish();
    }

    /** Serves one onboarding slide per page. */
    private final class SlideAdapter extends FragmentPagerAdapter {

        private final int[] layouts = {
                R.layout.fragment_welcome,
                R.layout.fragment_welcome_2,
                R.layout.fragment_welcome_3
        };

        SlideAdapter(FragmentManager fm) {
            super(fm);
        }

        @Override
        public Fragment getItem(int position) {
            int index = (position >= 0 && position < layouts.length) ? position : 0;
            return SlideFragment.create(index, layouts[index]);
        }

        @Override
        public int getCount() {
            return SLIDE_COUNT;
        }
    }

    /**
     * A single onboarding slide. Wires up two optional widgets if the inflated
     * layout contains them: a "get started" button and a demo REC button that
     * simply pages forward.
     */
    public static class SlideFragment extends Fragment {

        private static final String KEY_INDEX = "index";
        private static final String KEY_LAYOUT = "layout";

        public SlideFragment() {
        }

        static SlideFragment create(int index, int layoutRes) {
            SlideFragment fragment = new SlideFragment();
            Bundle args = new Bundle();
            args.putInt(KEY_INDEX, index);
            args.putInt(KEY_LAYOUT, layoutRes);
            fragment.setArguments(args);
            return fragment;
        }

        @Override
        public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
            Bundle args = getArguments();
            int layoutRes = args != null ? args.getInt(KEY_LAYOUT) : R.layout.fragment_welcome;
            final int index = args != null ? args.getInt(KEY_INDEX) : 0;
            View view = inflater.inflate(layoutRes, container, false);

            Button getStarted = view.findViewById(R.id.end_welcome);
            if (getStarted != null) {
                getStarted.setOnClickListener(v -> {
                    if (getActivity() instanceof WelcomeActivity) {
                        ((WelcomeActivity) getActivity()).finishOnboarding();
                    }
                });
            }

            ImageView demoRec = view.findViewById(R.id.demo_rec_widget);
            if (demoRec != null) {
                demoRec.setOnClickListener(v -> {
                    if (getActivity() instanceof WelcomeActivity) {
                        ((WelcomeActivity) getActivity()).goToNextSlide(index);
                    }
                });
            }

            return view;
        }
    }
}
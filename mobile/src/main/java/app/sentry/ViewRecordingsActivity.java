package app.sentry;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;

import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.tabs.TabLayout;
import app.sentry.models.Recording;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Browser for recorded clips. Two tabs: "All" browses the dated/hourly folder structure, and
 * "Starred" shows a flat list of saved clips. Supports folder navigation, playback, and
 * multi-select deletion, plus a delete-all action and a running count / total size in the header.
 */
public class ViewRecordingsActivity extends AppCompatActivity
        implements ViewRecordingsRecyclerViewAdapter.ItemListener {

    private static final int MENU_SELECT_ALL = 1;
    private static final int MENU_DELETE = 2;
    private static final int MENU_DELETE_ALL = 3;

    private static final int MODE_ALL = 0;
    private static final int MODE_STARRED = 1;

    private RecyclerView mRecyclerView;
    private ViewRecordingsRecyclerViewAdapter mAdapter;
    private View mLayoutListEmpty;
    private TabLayout mTabs;
    private TextView mStatsPath;
    private TextView mStatsSize;

    private File mRootDir;
    private File mCurrentDir;
    private int mMode = MODE_ALL;

    private BroadcastReceiver mBroadcastReceiver;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_view_recordings);

        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
        }

        mRootDir = Util.getVideosDirectoryPath();
        mCurrentDir = mRootDir;

        initRecyclerView();
        setupTabs();
        loadCurrent();
    }

    @Override
    protected void onStart() {
        super.onStart();
        registerBroadcastReceiver();
    }

    @Override
    protected void onStop() {
        unregisterBroadcastReceiver();
        super.onStop();
    }

    private void initRecyclerView() {
        mRecyclerView = findViewById(R.id.recycler_view);
        mLayoutListEmpty = findViewById(R.id.layout_list_empty);
        mStatsPath = findViewById(R.id.stats_path);
        mStatsSize = findViewById(R.id.stats_size);
        mRecyclerView.setLayoutManager(new LinearLayoutManager(this));
        mAdapter = new ViewRecordingsRecyclerViewAdapter(this, this);
        mRecyclerView.setAdapter(mAdapter);
    }

    private void setupTabs() {
        mTabs = findViewById(R.id.tabs);
        mTabs.addTab(mTabs.newTab().setText("All"));
        mTabs.addTab(mTabs.newTab().setText("Starred"));
        mTabs.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                mMode = tab.getPosition();
                if (mAdapter.isSelectionMode()) mAdapter.setSelectionMode(false);
                if (mMode == MODE_ALL) mCurrentDir = mRootDir;
                loadCurrent();
            }

            @Override
            public void onTabUnselected(TabLayout.Tab tab) {
            }

            @Override
            public void onTabReselected(TabLayout.Tab tab) {
            }
        });
    }

    private void loadCurrent() {
        if (mMode == MODE_STARRED) {
            loadStarred();
        } else {
            loadDir(mCurrentDir);
        }
    }

    /**
     * Loads and displays the contents of the given directory (folders first, then clips).
     */
    private void loadDir(File dir) {
        if (dir == null || !dir.isDirectory()) {
            dir = mRootDir;
        }
        mCurrentDir = dir;

        List<File> dirs = new ArrayList<>();
        List<File> videos = new ArrayList<>();
        File[] all = dir != null ? dir.listFiles() : null;
        if (all != null) {
            for (File f : all) {
                if (f.isDirectory()) {
                    dirs.add(f);
                } else if (f.getName().endsWith(".mp4")) {
                    videos.add(f);
                }
            }
        }

        Collections.sort(dirs, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return b.getName().compareTo(a.getName());
            }
        });
        Collections.sort(videos, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return Long.compare(b.lastModified(), a.lastModified());
            }
        });

        List<File> items = new ArrayList<>();
        items.addAll(dirs);
        items.addAll(videos);

        showItems(items);
        mStatsPath.setText(mCurrentDir == null || mCurrentDir.equals(mRootDir)
                ? "All clips" : relativePath(mCurrentDir));
    }

    private void loadStarred() {
        List<File> starred = new ArrayList<>();
        for (File f : Util.getAllRecordings()) {
            if (new Recording(f.getAbsolutePath()).isStarred()) starred.add(f);
        }
        Collections.sort(starred, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return Long.compare(b.lastModified(), a.lastModified());
            }
        });
        showItems(starred);
        mStatsPath.setText("Starred");
    }

    private void showItems(List<File> items) {
        mAdapter.setItems(items);
        boolean empty = items.isEmpty();
        mRecyclerView.setVisibility(empty ? View.GONE : View.VISIBLE);
        mLayoutListEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        updateHeader();
    }

    private void updateHeader() {
        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            if (mAdapter.isSelectionMode()) {
                actionBar.setTitle(mAdapter.getSelectedCount() + " selected");
            } else {
                int total = Util.getAllRecordings().size();
                actionBar.setTitle("Recordings (" + total + ")");
            }
        }
        long sizeMb = Util.getFolderSize(mRootDir);
        mStatsSize.setText(humanSize(sizeMb));
        invalidateOptionsMenu();
    }

    private String humanSize(long mb) {
        if (mb >= 1024) return String.format(Locale.US, "%.1f GB", mb / 1024f);
        return mb + " MB";
    }

    private String relativePath(File dir) {
        if (mRootDir == null) return dir.getName();
        String rootPath = mRootDir.getAbsolutePath();
        String path = dir.getAbsolutePath();
        if (path.startsWith(rootPath)) {
            String rel = path.substring(rootPath.length());
            rel = rel.replace(File.separator, " / ");
            if (rel.startsWith(" / ")) rel = rel.substring(3);
            return rel;
        }
        return dir.getName();
    }

    // --- Adapter callbacks ---

    @Override
    public void onItemClick(File file) {
        if (mAdapter.isSelectionMode()) {
            mAdapter.toggleSelection(file);
            if (mAdapter.getSelectedCount() == 0) {
                mAdapter.setSelectionMode(false);
            }
            updateHeader();
            return;
        }

        if (file.isDirectory()) {
            loadDir(file);
        } else {
            playRecording(file);
        }
    }

    @Override
    public void onItemLongClick(File file) {
        if (!mAdapter.isSelectionMode()) {
            mAdapter.setSelectionMode(true);
            mAdapter.toggleSelection(file);
            updateHeader();
        }
    }

    private void playRecording(File file) {
        try {
            Uri fileUri = FileProvider.getUriForFile(
                    this,
                    getApplicationContext().getPackageName() + ".provider",
                    file);
            Util.openFile(this, fileUri, "video/mp4");
        } catch (Exception e) {
            Util.showToast(this, "Cannot open recording.");
        }
    }

    // --- Options menu ---

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(Menu.NONE, MENU_SELECT_ALL, Menu.NONE, "Select all")
                .setShowAsActionFlags(MenuItem.SHOW_AS_ACTION_NEVER);
        menu.add(Menu.NONE, MENU_DELETE, Menu.NONE, "Delete")
                .setIcon(R.drawable.ic_delete)
                .setShowAsActionFlags(MenuItem.SHOW_AS_ACTION_ALWAYS);
        menu.add(Menu.NONE, MENU_DELETE_ALL, Menu.NONE, "Delete all")
                .setIcon(R.drawable.ic_delete)
                .setShowAsActionFlags(MenuItem.SHOW_AS_ACTION_ALWAYS);
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        boolean selecting = mAdapter != null && mAdapter.isSelectionMode();
        MenuItem selectAll = menu.findItem(MENU_SELECT_ALL);
        MenuItem delete = menu.findItem(MENU_DELETE);
        MenuItem deleteAll = menu.findItem(MENU_DELETE_ALL);
        if (selectAll != null) selectAll.setVisible(selecting);
        if (delete != null) delete.setVisible(selecting);
        if (deleteAll != null) deleteAll.setVisible(!selecting);
        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case android.R.id.home:
                if (mAdapter.isSelectionMode()) {
                    mAdapter.setSelectionMode(false);
                    updateHeader();
                } else if (mMode == MODE_ALL && !isAtRoot()) {
                    loadDir(mCurrentDir.getParentFile());
                } else {
                    finish();
                }
                return true;
            case MENU_SELECT_ALL:
                mAdapter.selectAll();
                updateHeader();
                return true;
            case MENU_DELETE:
                confirmDeleteSelected();
                return true;
            case MENU_DELETE_ALL:
                confirmDeleteAll();
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    @Override
    public void onBackPressed() {
        if (mAdapter.isSelectionMode()) {
            mAdapter.setSelectionMode(false);
            updateHeader();
        } else if (mMode == MODE_ALL && !isAtRoot()) {
            loadDir(mCurrentDir.getParentFile());
        } else {
            super.onBackPressed();
        }
    }

    private boolean isAtRoot() {
        return mCurrentDir == null || mRootDir == null || mCurrentDir.equals(mRootDir);
    }

    private void confirmDeleteSelected() {
        final List<File> selected = mAdapter.getSelectedFiles();
        if (selected.isEmpty()) return;

        String message = "Delete " + selected.size()
                + (selected.size() == 1 ? " item" : " items") + "? This cannot be undone.";

        new AlertDialog.Builder(this)
                .setTitle("Delete recordings")
                .setMessage(message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> deleteFiles(selected))
                .show();
    }

    private void confirmDeleteAll() {
        new AlertDialog.Builder(this)
                .setTitle("Delete all recordings")
                .setMessage("Delete ALL recordings? This cannot be undone.")
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> Util.deleteRecordings())
                .show();
    }

    private void deleteFiles(final List<File> selected) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                for (File f : selected) {
                    Util.deleteRecordingFileOrFolder(f);
                }
                Util.broadcastRecordingsChanged();
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        mAdapter.setSelectionMode(false);
                        loadCurrent();
                    }
                });
            }
        }).start();
    }

    // --- Auto refresh ---

    private void registerBroadcastReceiver() {
        mBroadcastReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (mAdapter != null && mAdapter.isSelectionMode()) return;
                loadCurrent();
            }
        };
        LocalBroadcastManager.getInstance(this).registerReceiver(
                mBroadcastReceiver,
                new IntentFilter(Util.ACTION_UPDATE_RECORDINGS_LIST));
    }

    private void unregisterBroadcastReceiver() {
        if (mBroadcastReceiver != null) {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(mBroadcastReceiver);
            mBroadcastReceiver = null;
        }
    }
}

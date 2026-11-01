package com.factorio;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.widget.ImageViewCompat;
import androidx.fragment.app.Fragment;
import androidx.preference.PreferenceManager;

import com.factorio.contentdialog.ContentDialog;
import com.factorio.core.AppUtils;
import com.factorio.core.Callback;
import com.factorio.core.FileUtils;
import com.factorio.core.HttpUtils;
import com.factorio.inputcontrols.ControlsProfile;
import com.factorio.inputcontrols.ExternalController;
import com.factorio.inputcontrols.InputControlsManager;
import com.factorio.widget.InputControlsView;
import com.factorio.widget.ColorPickerView;
import com.factorio.widget.SeekBar;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

public class InputControlsFragment extends Fragment {
    private static final String INPUT_CONTROLS_URL = "https://raw.githubusercontent.com/brunodev85/winlator/main/input_controls/%s";
    private InputControlsManager manager;
    private ControlsProfile currentProfile;
    private Runnable updateLayout;
    private Callback<ControlsProfile> importProfileCallback;
    private final int selectedProfileId;
    private final boolean openedFromGame;

    public InputControlsFragment(int selectedProfileId) {
        this(selectedProfileId, false);
    }

    /** openedFromGame: the selected profile is the one that is switched on in the running game. */
    public InputControlsFragment(int selectedProfileId, boolean openedFromGame) {
        this.selectedProfileId = selectedProfileId;
        this.openedFromGame = openedFromGame;
    }

    /** Id of the selected profile, 0 when none is selected (in the game that means the controls are off). */
    public int getCurrentProfileId() {
        return currentProfile != null ? currentProfile.id : 0;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(false);
        manager = new InputControlsManager(getContext());
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        ((AppCompatActivity)getActivity()).getSupportActionBar().setTitle(R.string.input_controls);
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        if (requestCode == MainActivity.OPEN_FILE_REQUEST_CODE && resultCode == Activity.RESULT_OK) {
            try {
                ControlsProfile importedProfile = manager.importProfile(new JSONObject(FileUtils.readString(getContext(), data.getData())));
                if (importProfileCallback != null) importProfileCallback.call(importedProfile);
            }
            catch (Exception e) {
                AppUtils.showToast(getContext(), R.string.unable_to_import_profile);
            }
            importProfileCallback = null;
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.input_controls_fragment, container, false);
        final Context context = getContext();
        final SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);

        currentProfile = selectedProfileId > 0 ? manager.getProfile(selectedProfileId) : null;

        final Spinner sProfile = view.findViewById(R.id.SProfile);
        loadProfileSpinner(sProfile);

        // everything about the mouse and the cursor lives in one frame; speed, size, color and the switches are
        // common settings, only "disable mouse input" belongs to the selected profile
        final SeekBar sbCursorSpeed = view.findViewById(R.id.SBCursorSpeed);
        sbCursorSpeed.setValue(preferences.getFloat("cursor_speed", 1.0f) * 100);
        sbCursorSpeed.setOnValueChangeListener((seekBar, value) -> preferences.edit().putFloat("cursor_speed", value / 100.0f).apply());

        final SeekBar sbCursorSize = view.findViewById(R.id.SBCursorSize);
        sbCursorSize.setValue(preferences.getFloat("cursor_scale", 1.0f) * 100);
        sbCursorSize.setOnValueChangeListener((seekBar, value) -> preferences.edit().putFloat("cursor_scale", value / 100.0f).apply());

        final ColorPickerView cpvCursorColor = view.findViewById(R.id.CPVCursorColor);
        cpvCursorColor.setPalette(0xffffff, 0x000000, 0x651fff, 0xffea00, 0xff9100, 0xf50057, 0x00b0ff, 0x1de9b6);
        cpvCursorColor.setColor(preferences.getInt("cursor_color", 0xffffff));
        cpvCursorColor.setOnColorChangedListener((color) -> preferences.edit().putInt("cursor_color", color).apply());

        CheckBox cbMoveCursorToTouchpoint = view.findViewById(R.id.CBMoveCursorToTouchpoint);
        cbMoveCursorToTouchpoint.setChecked(preferences.getBoolean("move_cursor_to_touchpoint", false));
        cbMoveCursorToTouchpoint.setOnCheckedChangeListener((buttonView, isChecked) -> preferences.edit().putBoolean("move_cursor_to_touchpoint", isChecked).apply());

        CheckBox cbCapturePointerOnExternalMouse = view.findViewById(R.id.CBCapturePointerOnExternalMouse);
        cbCapturePointerOnExternalMouse.setChecked(preferences.getBoolean("capture_pointer_on_external_mouse", true));
        cbCapturePointerOnExternalMouse.setOnCheckedChangeListener((buttonView, isChecked) -> preferences.edit().putBoolean("capture_pointer_on_external_mouse", isChecked).apply());

        CheckBox cbDisableMouseInput = view.findViewById(R.id.CBDisableMouseInput);
        cbDisableMouseInput.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (currentProfile != null) {
                currentProfile.setDisableMouseInput(isChecked);
                currentProfile.save();
            }
        });

        updateLayout = () -> {
            cbDisableMouseInput.setChecked(currentProfile != null && currentProfile.isDisableMouseInput());
            loadExternalControllers(view);
        };

        updateLayout.run();

        // these three used to live in the in-game dialog; the game applies them when it gets back from this screen
        CheckBox cbShowTouchscreenControls = view.findViewById(R.id.CBShowTouchscreenControls);
        cbShowTouchscreenControls.setChecked(preferences.getBoolean("show_touchscreen_controls", true));
        cbShowTouchscreenControls.setOnCheckedChangeListener((buttonView, isChecked) -> preferences.edit().putBoolean("show_touchscreen_controls", isChecked).apply());

        CheckBox cbShowControlsWithGamepad = view.findViewById(R.id.CBShowControlsWithGamepad);
        cbShowControlsWithGamepad.setChecked(preferences.getBoolean("show_controls_with_gamepad", false));
        cbShowControlsWithGamepad.setOnCheckedChangeListener((buttonView, isChecked) -> preferences.edit().putBoolean("show_controls_with_gamepad", isChecked).apply());

        // Relative mouse movement only worked through Wine's winhandler.exe: hidden for the native game.
        view.findViewById(R.id.CBRelativeMouseMovement).setVisibility(View.GONE);

        view.findViewById(R.id.BTAddProfile).setOnClickListener((v) -> ContentDialog.prompt(context, R.string.profile_name, null, (name) -> {
            currentProfile = manager.createProfile(name);
            loadProfileSpinner(sProfile);
            updateLayout.run();
        }));

        view.findViewById(R.id.BTEditProfile).setOnClickListener((v) -> {
            if (currentProfile != null) {
                ContentDialog.prompt(context, R.string.profile_name, currentProfile.getName(), (name) -> {
                    currentProfile.setName(name);
                    currentProfile.save();
                    loadProfileSpinner(sProfile);
                });
            }
            else AppUtils.showToast(context, R.string.no_profile_selected);
        });

        view.findViewById(R.id.BTDuplicateProfile).setOnClickListener((v) -> {
            if (currentProfile != null) {
                ContentDialog.confirm(context, R.string.do_you_want_to_duplicate_this_profile, () -> {
                    currentProfile = manager.duplicateProfile(currentProfile);
                    loadProfileSpinner(sProfile);
                    updateLayout.run();
                });
            }
            else AppUtils.showToast(context, R.string.no_profile_selected);
        });

        view.findViewById(R.id.BTRemoveProfile).setOnClickListener((v) -> {
            if (currentProfile != null) {
                ContentDialog.confirm(context, R.string.do_you_want_to_remove_this_profile, () -> {
                    manager.removeProfile(currentProfile);
                    currentProfile = null;
                    loadProfileSpinner(sProfile);
                    updateLayout.run();
                });
            }
            else AppUtils.showToast(context, R.string.no_profile_selected);
        });

        view.findViewById(R.id.BTImportProfile).setOnClickListener((v) -> {
            PopupMenu popupMenu = new PopupMenu(context, v);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) popupMenu.setForceShowIcon(true);
            popupMenu.inflate(R.menu.open_file_popup_menu);
            popupMenu.setOnMenuItemClickListener((menuItem) -> {
                int itemId = menuItem.getItemId();
                if (itemId == R.id.menu_item_open_file) {
                    openProfileFile(sProfile);
                }
                else if (itemId == R.id.menu_item_download_file) {
                    downloadProfileList(sProfile);
                }
                return true;
            });
            popupMenu.show();
        });

        view.findViewById(R.id.BTExportProfile).setOnClickListener((v) -> {
            if (currentProfile != null) {
                File exportedFile = manager.exportProfile(currentProfile);
                if (exportedFile != null) {
                    String path = exportedFile.getPath().substring(exportedFile.getPath().indexOf(Environment.DIRECTORY_DOWNLOADS));
                    AppUtils.showToast(context, context.getString(R.string.profile_exported_to)+" "+path);
                }
            }
            else AppUtils.showToast(context, R.string.no_profile_selected);
        });

        view.findViewById(R.id.BTControlsEditor).setOnClickListener((v) -> {
            if (currentProfile != null) {
                Intent intent = new Intent(context, ControlsEditorActivity.class);
                intent.putExtra("profile_id", currentProfile.id);
                startActivity(intent);
            }
            else AppUtils.showToast(context, R.string.no_profile_selected);
        });

        return view;
    }

    private void openProfileFile(Spinner sProfile) {
        importProfileCallback = (importedProfile) -> {
            currentProfile = importedProfile;
            loadProfileSpinner(sProfile);
            updateLayout.run();
        };
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        getActivity().startActivityFromFragment(this, intent, MainActivity.OPEN_FILE_REQUEST_CODE);
    }

    private void downloadSelectedProfiles(final Spinner sProfile, String[] items, final ArrayList<Integer> positions) {
        final MainActivity activity = (MainActivity)getActivity();
        activity.preloaderDialog.show(R.string.downloading_file);
        currentProfile = null;
        final AtomicInteger processedItemCount = new AtomicInteger();

        for (int position : positions) {
            HttpUtils.download(String.format(INPUT_CONTROLS_URL, items[position]), (content) -> {
                try {
                    if (content != null) manager.importProfile(new JSONObject(content));
                }
                catch (JSONException e) {}
                if (processedItemCount.incrementAndGet() == positions.size()) {
                    activity.runOnUiThread(() -> {
                        activity.preloaderDialog.close();
                        loadProfileSpinner(sProfile);
                        updateLayout.run();
                    });
                }
            });
        }
    }

    private void downloadProfileList(final Spinner sProfile) {
        final MainActivity activity = (MainActivity)getActivity();
        activity.preloaderDialog.show(R.string.loading);
        HttpUtils.download(String.format(INPUT_CONTROLS_URL, "index.txt"), (content) -> activity.runOnUiThread(() -> {
            activity.preloaderDialog.close();
            if (content != null) {
                final String[] items = content.split("\n");
                ContentDialog.showSelectionList(activity, R.string.import_profile, items, true, (positions) -> {
                    if (!positions.isEmpty()) {
                        ContentDialog.confirm(activity, R.string.do_you_want_to_download_the_selected_profiles, () -> downloadSelectedProfiles(sProfile, items, positions));
                    }
                });
            }
            else AppUtils.showToast(activity, R.string.a_network_error_occurred);
        }));
    }

    @Override
    public void onStart() {
        super.onStart();
        if (updateLayout != null) updateLayout.run();
    }

    private void loadProfileSpinner(Spinner spinner) {
        final ArrayList<ControlsProfile> profiles = manager.getProfiles();
        ArrayList<String> values = new ArrayList<>();
        values.add("-- "+getString(openedFromGame ? R.string.disabled : R.string.select_profile)+" --");

        int selectedPosition = 0;
        for (int i = 0; i < profiles.size(); i++) {
            ControlsProfile profile = profiles.get(i);
            if (profile == currentProfile) selectedPosition = i + 1;
            values.add(profile.getName());
        }

        spinner.setAdapter(new ArrayAdapter<>(getContext(), android.R.layout.simple_spinner_dropdown_item, values));
        spinner.setSelection(selectedPosition, false);
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                currentProfile = position > 0 ? profiles.get(position - 1) : null;
                updateLayout.run();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    private void loadExternalControllers(final View view) {
        LinearLayout container = view.findViewById(R.id.LLExternalControllers);
        container.removeAllViews();
        Context context = getContext();
        LayoutInflater inflater = LayoutInflater.from(context);
        ArrayList<ExternalController> connectedControllers = ExternalController.getControllers();

        ArrayList<ExternalController> controllers = currentProfile != null ? currentProfile.loadControllers() : new ArrayList<>();
        for (ExternalController controller : connectedControllers) {
            if (!controllers.contains(controller)) controllers.add(controller);
        }

        if (!controllers.isEmpty()) {
            view.findViewById(R.id.TVEmptyText).setVisibility(View.GONE);
            String bindingsText = context.getString(R.string.bindings).toLowerCase(Locale.ENGLISH);
            for (final ExternalController controller : controllers) {
                View itemView = inflater.inflate(R.layout.external_controller_list_item, container, false);
                ((TextView)itemView.findViewById(R.id.TVTitle)).setText(controller.getName());

                int controllerBindingCount = controller.getControllerBindingCount();
                ((TextView)itemView.findViewById(R.id.TVSubtitle)).setText(controllerBindingCount+" "+bindingsText);

                ImageView imageView = itemView.findViewById(R.id.ImageView);
                int tintColor = AppUtils.getThemeColor(context, controller.isConnected() ? R.attr.colorAccent : R.attr.colorError);
                ImageViewCompat.setImageTintList(imageView, ColorStateList.valueOf(tintColor));

                if (controllerBindingCount > 0) {
                    ImageButton removeButton = itemView.findViewById(R.id.BTRemove);
                    removeButton.setVisibility(View.VISIBLE);
                    removeButton.setOnClickListener((v) -> ContentDialog.confirm(getContext(), R.string.do_you_want_to_remove_this_controller, () -> {
                        currentProfile.removeController(controller);
                        currentProfile.save();
                        loadExternalControllers(view);
                    }));
                }

                itemView.setOnClickListener((v) -> {
                    if (currentProfile != null) {
                        Intent intent = new Intent(getContext(), ExternalControllerBindingsActivity.class);
                        intent.putExtra("profile_id", currentProfile.id);
                        intent.putExtra("controller_id", controller.getId());
                        startActivity(intent);
                    }
                    else AppUtils.showToast(getContext(), R.string.no_profile_selected);
                });

                container.addView(itemView);
            }
        }
        else view.findViewById(R.id.TVEmptyText).setVisibility(View.VISIBLE);
    }
}

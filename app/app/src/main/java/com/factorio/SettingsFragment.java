package com.factorio;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioGroup;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import android.app.Activity;
import androidx.fragment.app.Fragment;
import androidx.preference.PreferenceManager;

import com.factorio.container.Container;
import com.factorio.container.ContainerManager;
import com.factorio.container.GraphicsDriverPicker;
import com.factorio.container.GraphicsDrivers;
import com.factorio.contentdialog.AddEnvVarDialog;
import com.factorio.contentdialog.AudioDriverConfigDialog;
import com.factorio.contentdialog.ContentDialog;
import com.factorio.core.AppUtils;
import com.factorio.core.EnvVars;
import com.factorio.core.LocaleHelper;
import com.factorio.core.ScreenResolution;
import com.factorio.core.StringUtils;
import com.factorio.widget.ColorPickerView;
import com.factorio.widget.EnvVarsView;
import com.factorio.widget.LogView;
import com.factorio.widget.SeekBar;

public class SettingsFragment extends Fragment {
    private SharedPreferences preferences;
    private Container container; // the single built-in container (null until the system files are installed)

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        ((AppCompatActivity)getActivity()).getSupportActionBar().setTitle(R.string.settings);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup root, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.settings_fragment, root, false);
        final Context context = getContext();
        preferences = PreferenceManager.getDefaultSharedPreferences(context);
        final MainActivity activity = (MainActivity)getActivity();

        // ---- container settings (screen size, drivers, environment variables) ----
        container = (new ContainerManager(context)).getMainContainer();
        final View flContainerSettings = view.findViewById(R.id.FLContainerSettings);
        GraphicsDriverPicker graphicsDriverPicker = null;
        Spinner sAudioDriver = view.findViewById(R.id.SAudioDriver);
        final View vAudioDriverConfig = view.findViewById(R.id.BTAudioDriverConfig);
        final EnvVarsView envVarsView = view.findViewById(R.id.EnvVarsView);
        if (container != null) {
            loadScreenSizeSpinner(view, container.getScreenSize());
            setupResolutionButtons(view, context);
            graphicsDriverPicker = new GraphicsDriverPicker(view.findViewById(R.id.LLGraphicsDriver), container);
            ((TextView)view.findViewById(R.id.TVDetectedGpu)).setText(GraphicsDriverPicker.getDetectedGpuText(context));
            AppUtils.setSpinnerSelectionFromIdentifier(sAudioDriver, container.getAudioDriver());
            vAudioDriverConfig.setTag(container.getAudioDriverConfig());
            vAudioDriverConfig.setOnClickListener((v) -> (new AudioDriverConfigDialog(v)).show());
            envVarsView.setEnvVars(new EnvVars(container.getEnvVars()));
            view.findViewById(R.id.BTAddEnvVar).setOnClickListener((v) -> (new AddEnvVarDialog(context, envVarsView)).show());
        }
        else {
            flContainerSettings.setVisibility(View.GONE);
            view.findViewById(R.id.FLEnvVars).setVisibility(View.GONE);
        }
        final GraphicsDriverPicker finalGraphicsDriverPicker = graphicsDriverPicker;

        // ---- bottom section "variables and logging": folded until the header is tapped ----
        final View llAdvancedBody = view.findViewById(R.id.LLAdvancedBody);
        final TextView tvAdvancedArrow = view.findViewById(R.id.TVAdvancedArrow);
        view.findViewById(R.id.LLAdvancedHeader).setOnClickListener((v) -> {
            boolean expand = llAdvancedBody.getVisibility() != View.VISIBLE;
            android.transition.TransitionManager.beginDelayedTransition((ViewGroup)llAdvancedBody.getParent());
            llAdvancedBody.setVisibility(expand ? View.VISIBLE : View.GONE);
            tvAdvancedArrow.setText(expand ? "\u25B2" : "\u25BC");
        });

        final CheckBox cbEnableDebugLogs = view.findViewById(R.id.CBEnableDebugLogs);
        cbEnableDebugLogs.setChecked(preferences.getBoolean("enable_debug_logs", false));

        final CheckBox cbSaveLogsToFile = view.findViewById(R.id.CBSaveLogsToFile);
        cbSaveLogsToFile.setChecked(preferences.getBoolean("save_logs_to_file", false));

        final EditText etLogFile = view.findViewById(R.id.ETLogFile);
        final String defaultLogPath = LogView.getLogFile().getPath();
        etLogFile.setText(preferences.getString("log_file", defaultLogPath));
        etLogFile.setVisibility(cbSaveLogsToFile.isChecked() ? View.VISIBLE : View.GONE);
        cbSaveLogsToFile.setOnCheckedChangeListener((buttonView, isChecked) -> etLogFile.setVisibility(isChecked ? View.VISIBLE : View.GONE));

        final Spinner sLanguage = view.findViewById(R.id.SLanguage);
        sLanguage.setSelection(LocaleHelper.getLocaleIndex(context));
        final int oldLCIndex = sLanguage.getSelectedItemPosition();

        view.findViewById(R.id.BTDiagnostics).setOnClickListener((v) -> startDiagnostics(com.factorio.core.Diagnostics.MODE_SHORT));
        view.findViewById(R.id.BTDiagnosticsLong).setOnClickListener((v) -> startDiagnostics(com.factorio.core.Diagnostics.MODE_LONG));
        view.findViewById(R.id.BTDiagnosticsPerf).setOnClickListener((v) -> startDiagnostics(com.factorio.core.Diagnostics.MODE_PERF));
        final CheckBox cbPerfLog = view.findViewById(R.id.CBPerfLog);
        cbPerfLog.setChecked(preferences.getBoolean("perf_log", false));
        cbPerfLog.setOnCheckedChangeListener((buttonView, isChecked) -> preferences.edit().putBoolean("perf_log", isChecked).apply());

        view.findViewById(R.id.BTConfirm).setOnClickListener((v) -> {
            if (container != null) {
                container.setScreenSize(getScreenSize(view));
                container.setEnvVars(envVarsView.getEnvVars());
                finalGraphicsDriverPicker.applyTo(container);
                container.setAudioDriver(StringUtils.parseIdentifier(sAudioDriver.getSelectedItem()));
                container.setAudioDriverConfig(vAudioDriverConfig.getTag().toString());
                container.saveData();
            }

            SharedPreferences.Editor editor = preferences.edit();
            editor.putBoolean("enable_debug_logs", cbEnableDebugLogs.isChecked());
            editor.putBoolean("save_logs_to_file", cbSaveLogsToFile.isChecked());

            int newLCIndex = sLanguage.getSelectedItemPosition();
            editor.putInt("lc_index", newLCIndex);
            boolean restartApp = oldLCIndex != newLCIndex;

            String logPath = etLogFile.getText().toString().trim();
            if (!logPath.equals(defaultLogPath) && !logPath.isEmpty()) {
                editor.putString("log_file", logPath);
            }
            else editor.remove("log_file");

            if (editor.commit()) {
                if (restartApp) AppUtils.restartActivity(activity); // language
                else if (activity.isOpenedFromGame()) {
                    // the game is running with the old settings
                    ContentDialog.confirm(context, R.string.the_settings_have_been_changed_do_you_want_to_restart_the_app, () -> AppUtils.restartApplication(context));
                }
                else activity.startGame();
            }
        });

        return view;
    }

    /** 720p / 1080p / native: puts the computed size into the custom width x height fields. */
    private static void setupResolutionButtons(final View view, final Context context) {
        final int[] ids = {R.id.BTSetRes720, R.id.BTSetRes1080, R.id.BTSetResNative};
        for (int i = 0; i < ids.length; i++) {
            final int mode = i;
            view.findViewById(ids[i]).setOnClickListener((v) -> {
                String[] size = ScreenResolution.forMode(context, mode).split("x");
                ((Spinner)view.findViewById(R.id.SScreenSize)).setSelection(0); // "Custom"
                view.findViewById(R.id.LLCustomScreenSize).setVisibility(View.VISIBLE);
                ((EditText)view.findViewById(R.id.ETScreenWidth)).setText(size[0]);
                ((EditText)view.findViewById(R.id.ETScreenHeight)).setText(size[1]);
            });
        }
    }

    private static String getScreenSize(View view) {
        Spinner sScreenSize = view.findViewById(R.id.SScreenSize);
        String value = sScreenSize.getSelectedItem().toString();
        if (sScreenSize.getSelectedItemPosition() == 0) {
            value = Container.DEFAULT_SCREEN_SIZE;
            String strWidth = ((EditText)view.findViewById(R.id.ETScreenWidth)).getText().toString().trim();
            String strHeight = ((EditText)view.findViewById(R.id.ETScreenHeight)).getText().toString().trim();
            if (strWidth.matches("[0-9]+") && strHeight.matches("[0-9]+")) {
                int width = Integer.parseInt(strWidth);
                int height = Integer.parseInt(strHeight);
                if ((width % 2) == 0 && (height % 2) == 0) return width+"x"+height;
            }
        }
        return StringUtils.parseIdentifier(value);
    }

    private static void loadScreenSizeSpinner(View view, String selectedValue) {
        final Spinner sScreenSize = view.findViewById(R.id.SScreenSize);

        final LinearLayout llCustomScreenSize = view.findViewById(R.id.LLCustomScreenSize);
        sScreenSize.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                llCustomScreenSize.setVisibility(sScreenSize.getSelectedItemPosition() == 0 ? View.VISIBLE : View.GONE);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        boolean found = AppUtils.setSpinnerSelectionFromIdentifier(sScreenSize, selectedValue);
        if (!found) {
            sScreenSize.setSelection(0);
            String[] screenSize = selectedValue.split("x");
            ((EditText)view.findViewById(R.id.ETScreenWidth)).setText(screenSize[0]);
            ((EditText)view.findViewById(R.id.ETScreenHeight)).setText(screenSize[1]);
        }
    }

    private void startDiagnostics(final int mode) {
        final Activity activity = getActivity();
        if (activity == null) return;
        final int count = com.factorio.core.Diagnostics.buildVariants(activity, mode).size();
        final int minutes = com.factorio.core.Diagnostics.estimateMinutes(activity, mode);
        String message;
        int title;
        if (mode == com.factorio.core.Diagnostics.MODE_PERF) {
            // first the save: the tests should measure the base that is slow (a light save gives 60 FPS anyway)
            final java.util.List<java.io.File> saves = com.factorio.core.Diagnostics.listSaves(activity);
            if (saves.size() > 1 && chosenSave == null) {
                String[] names = new String[saves.size()];
                java.text.DateFormat format = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT);
                for (int i = 0; i < names.length; i++) {
                    names[i] = saves.get(i).getName()+"  ("+(saves.get(i).length() >> 20)+" MB, "+format.format(new java.util.Date(saves.get(i).lastModified()))+")";
                }
                new androidx.appcompat.app.AlertDialog.Builder(activity)
                    .setTitle(R.string.diagnostics_perf_choose_save)
                    .setItems(names, (dialog, which) -> {
                        chosenSave = saves.get(which);
                        startDiagnostics(mode);
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
                return;
            }
            title = R.string.diagnostics_perf;
            java.io.File save = chosenSave != null ? chosenSave : com.factorio.core.Diagnostics.findNewestSave(activity);
            String dri = com.factorio.core.Diagnostics.findZinkDri(activity);
            message = getString(R.string.diagnostics_perf_confirm, count, minutes,
                save != null ? save.getName() : getString(R.string.diagnostics_perf_no_save),
                dri != null ? "Zink "+dri : "Zink "+com.factorio.core.DefaultVersion.ZINK+" (xlib)");
        }
        else {
            title = mode == com.factorio.core.Diagnostics.MODE_LONG ? R.string.diagnostics_long : R.string.diagnostics;
            message = getString(R.string.diagnostics_confirm, count, minutes);
        }
        new androidx.appcompat.app.AlertDialog.Builder(activity)
            .setTitle(title)
            .setMessage(message)
            .setNegativeButton(android.R.string.cancel, (dialog, which) -> chosenSave = null)
            .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                java.io.File save = chosenSave;
                chosenSave = null;
                com.factorio.core.Diagnostics.start(activity, mode, save);
            })
            .show();
    }

    private java.io.File chosenSave;
}

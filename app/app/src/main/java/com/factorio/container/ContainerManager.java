package com.factorio.container;

import com.factorio.core.AppExecutors;
import android.content.Context;
import android.os.Handler;

import com.factorio.core.Callback;
import com.factorio.core.EnvVars;
import com.factorio.core.FileUtils;
import com.factorio.xenvironment.RootFS;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.concurrent.Executors;

public class ContainerManager {
    private final ArrayList<Container> containers = new ArrayList<>();
    private int maxContainerId = 0;
    private final File homeDir;
    private final Context context;

    public ContainerManager(Context context) {
        this.context = context;
        File rootDir = RootFS.find(context).getRootDir();
        homeDir = new File(rootDir, "home");
        loadContainers();
    }

    public Context getContext() {
        return context;
    }

    public ArrayList<Container> getContainers() {
        return containers;
    }

    private void loadContainers() {
        containers.clear();
        maxContainerId = 0;

        try {
            File[] files = homeDir.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.isDirectory()) {
                        if (file.getName().startsWith(RootFS.USER+"-")) {
                            Container container = new Container(Integer.parseInt(file.getName().replace(RootFS.USER+"-", "")));
                            container.setRootDir(new File(homeDir, RootFS.USER+"-"+container.id));
                            JSONObject data = new JSONObject(FileUtils.readString(container.getConfigFile()));
                            container.loadData(data);
                            containers.add(container);
                            maxContainerId = Math.max(maxContainerId, container.id);
                        }
                    }
                }
            }
        }
        catch (JSONException e) {}
    }

    public void activateContainer(Container container) {
        container.setRootDir(new File(homeDir, RootFS.USER+"-"+container.id));
        File file = new File(homeDir, RootFS.USER);
        file.delete();
        FileUtils.symlink(RootFS.USER+"-"+container.id, file.getPath());
    }

    public void createContainerAsync(final JSONObject data, Callback<Container> callback) {
        final Handler handler = new Handler();
        AppExecutors.execute(() -> {
            final Container container = createContainer(data);
            handler.post(() -> callback.call(container));
        });
    }

    private Container createContainer(JSONObject data) {
        try {
            int id = maxContainerId + 1;
            data.put("id", id);

            File containerDir = new File(homeDir, RootFS.USER+"-"+id);
            if (!containerDir.mkdirs()) return null;

            Container container = new Container(id);
            container.setRootDir(containerDir);
            container.loadData(data);

            container.saveData();
            maxContainerId++;
            containers.add(container);
            return container;
        }
        catch (JSONException e) {}
        return null;
    }

    /**
     * The app has a single built-in container (the game). Prefers the one that has NATIVE_EXEC,
     * falls back to the first one, or null when nothing has been created yet.
     */
    public Container getMainContainer() {
        for (Container container : containers) {
            String nativeExec = (new EnvVars(container.getEnvVars())).get("NATIVE_EXEC");
            if (nativeExec != null && !nativeExec.isEmpty()) return container;
        }
        return containers.isEmpty() ? null : containers.get(0);
    }

    public int getNextContainerId() {
        return maxContainerId + 1;
    }

    public Container getContainerById(int id) {
        for (Container container : containers) if (container.id == id) return container;
        return null;
    }
}

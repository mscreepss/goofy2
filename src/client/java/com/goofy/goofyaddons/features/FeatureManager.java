package com.goofy.goofyaddons.features;

import com.goofy.goofyaddons.features.bookflipper.BazaarFlipper;

import java.util.ArrayList;
import java.util.List;


public class FeatureManager {
    List<Feature> featureList = new ArrayList<>();
    Feature currentFeature = null;

    /**
     * Makro çalışıyor ama duraklatılmış mı? (ör. ScheduledReboot Hub'a çıkarken
     * pause() çağırıyor.) EconomyTracker uptime sayacını buna bakarak durduruyor -
     * duraklatılmış geçen süre çalışma süresi sayılmaz.
     */
    private boolean paused = false;

    public static final FeatureManager INSTANCE = new FeatureManager();

    private FeatureManager() {
        featureList.add(new BazaarFlipper());
    }

    public void onTick() {
        if (currentFeature == null) return;
        currentFeature.onTick();
    }

    public void start(String name) {
        currentFeature = featureList.stream().filter(feature -> feature.name().equals(name)).findFirst().orElse(null);
        if (currentFeature == null) return;
        paused = false;
        currentFeature.start();
    }

    public void stop() {
        if (currentFeature == null) return;
        currentFeature.stop();
        currentFeature = null;
        paused = false;
    }

    public void pause() {
        if (currentFeature == null) return;
        currentFeature.pause();
        paused = true;
    }

    public void resume() {
        if (currentFeature == null) return;
        currentFeature.resume();
        paused = false;
    }

    public Feature getCurrentFeature() {
        return currentFeature;
    }

    public boolean isMacroRunning() {
        return currentFeature != null;
    }

    /** Makro hem seçili hem de duraklatılmamış mı? Uptime sayacı yalnızca bu true iken ilerler. */
    public boolean isMacroActive() {
        return currentFeature != null && !paused;
    }

    public boolean isPaused() {
        return paused;
    }

}

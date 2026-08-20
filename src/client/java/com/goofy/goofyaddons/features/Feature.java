package com.goofy.goofyaddons.features;

public interface Feature {
    String name();

    void stop();

    void start();

    void pause();

    void resume();

    void onTick();
}

package com.matrix.agent.platform.weather;

/** Stable reason codes; provider messages and HTTP bodies never become model instructions. */
public final class WeatherFailure extends Exception {
    private final String code;
    public WeatherFailure(String code) { super(code); this.code = code; }
    public String code() { return code; }
}

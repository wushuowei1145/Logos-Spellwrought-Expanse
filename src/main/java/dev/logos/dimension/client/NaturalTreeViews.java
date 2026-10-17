package dev.logos.dimension.client;
/** Historical gallery aliases now show native generated woods. */
public abstract class NaturalTreeViews extends JourneyViews {
    public static class First extends NaturalTreeViews{}
    public static class Second extends NaturalTreeViews{protected int season(){return 2;}}
}

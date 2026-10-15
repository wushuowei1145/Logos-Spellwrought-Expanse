package dev.logos.dimension.client;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;

/** Combined 0.1.3 verification; feature groups remain individually runnable. */
public final class VisualRevisionViews {
    @LDLRegisterClient(name="revision_trees_a",group="visual_revision",registry=UIScenario.REGISTRY,environment=RegistrationEnvironment.DEV_ONLY)
    public static class TreesA extends NaturalTreeViews.First {}
    @LDLRegisterClient(name="revision_trees_b",group="visual_revision",registry=UIScenario.REGISTRY,environment=RegistrationEnvironment.DEV_ONLY)
    public static class TreesB extends NaturalTreeViews.Second {}
    @LDLRegisterClient(name="revision_materials",group="visual_revision",registry=UIScenario.REGISTRY,environment=RegistrationEnvironment.DEV_ONLY)
    public static class Materials extends ModelPolishViews.Materials {}
    @LDLRegisterClient(name="revision_leaves_a",group="visual_revision",registry=UIScenario.REGISTRY,environment=RegistrationEnvironment.DEV_ONLY)
    public static class LeavesA extends IndividualModelViews.First {}
    @LDLRegisterClient(name="revision_leaves_b",group="visual_revision",registry=UIScenario.REGISTRY,environment=RegistrationEnvironment.DEV_ONLY)
    public static class LeavesB extends IndividualModelViews.Second {}
    @LDLRegisterClient(name="revision_leaves_c",group="visual_revision",registry=UIScenario.REGISTRY,environment=RegistrationEnvironment.DEV_ONLY)
    public static class LeavesC extends IndividualModelViews.Third {}
    @LDLRegisterClient(name="revision_glass_array",group="visual_revision",registry=UIScenario.REGISTRY,environment=RegistrationEnvironment.DEV_ONLY)
    public static class Glass extends GlassArrayView {}
    @LDLRegisterClient(name="revision_sky_upper",group="visual_revision",registry=UIScenario.REGISTRY,environment=RegistrationEnvironment.DEV_ONLY)
    public static class OpenAbove extends OpenSkyViews.Upper {}
    @LDLRegisterClient(name="revision_sky_middle",group="visual_revision",registry=UIScenario.REGISTRY,environment=RegistrationEnvironment.DEV_ONLY)
    public static class OpenMiddle extends OpenSkyViews.Middle {}
    @LDLRegisterClient(name="revision_landscape",group="visual_revision",registry=UIScenario.REGISTRY,environment=RegistrationEnvironment.DEV_ONLY)
    public static class Landscape extends CloudseaViews.Folds {}
}

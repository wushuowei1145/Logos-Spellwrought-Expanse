package dev.logos.dimension.client;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;

/** Combined release verification. Feature PRs keep their independent scenario groups. */
public final class RefinementViews {
    @LDLRegisterClient(name="refinement_spell",group="refinement",registry=UIScenario.REGISTRY,environment=RegistrationEnvironment.DEV_ONLY)
    public static class Spell extends ArcaneArrayView {}
    @LDLRegisterClient(name="refinement_leaf_1",group="refinement",registry=UIScenario.REGISTRY,environment=RegistrationEnvironment.DEV_ONLY)
    public static class Leaf1 extends IndividualModelViews.First {}
    @LDLRegisterClient(name="refinement_leaf_2",group="refinement",registry=UIScenario.REGISTRY,environment=RegistrationEnvironment.DEV_ONLY)
    public static class Leaf2 extends IndividualModelViews.Second {}
    @LDLRegisterClient(name="refinement_leaf_3",group="refinement",registry=UIScenario.REGISTRY,environment=RegistrationEnvironment.DEV_ONLY)
    public static class Leaf3 extends IndividualModelViews.Third {}
    @LDLRegisterClient(name="refinement_materials",group="refinement",registry=UIScenario.REGISTRY,environment=RegistrationEnvironment.DEV_ONLY)
    public static class Materials extends ModelPolishViews.Materials {}
    @LDLRegisterClient(name="refinement_cloud_top",group="refinement",registry=UIScenario.REGISTRY,environment=RegistrationEnvironment.DEV_ONLY)
    public static class CloudTop extends OpenSkyViews.Upper {}
    @LDLRegisterClient(name="refinement_cloud_inside",group="refinement",registry=UIScenario.REGISTRY,environment=RegistrationEnvironment.DEV_ONLY)
    public static class CloudInside extends OpenSkyViews.Middle {}
    @LDLRegisterClient(name="refinement_cleft",group="refinement",registry=UIScenario.REGISTRY,environment=RegistrationEnvironment.DEV_ONLY)
    public static class Cleft extends OpenSkyViews.Whale {}
}

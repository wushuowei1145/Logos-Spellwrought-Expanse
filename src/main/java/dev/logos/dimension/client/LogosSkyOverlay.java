package dev.logos.dimension.client;

import java.lang.reflect.Method;
import net.minecraft.client.renderer.SubmitNodeCollector;

/** Use the installed Logos shader bridge without packaging its classes or depending on Iris. */
final class LogosSkyOverlay {
    private static final Method COLLECTOR = findCollector();

    private static Method findCollector() {
        try {
            return Class.forName("dev.logos.client.ShaderPackOverlay")
                    .getMethod("collector", SubmitNodeCollector.class);
        } catch (ClassNotFoundException | NoSuchMethodException oldLogos) {
            // Early 0.5.0 builds predate this optional API. Vanilla rendering still works there.
            return null;
        }
    }

    static SubmitNodeCollector collector(SubmitNodeCollector out) {
        if (COLLECTOR == null) return out;
        try {
            return (SubmitNodeCollector) COLLECTOR.invoke(null, out);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("Logos sky overlay failed", error);
        }
    }
    private LogosSkyOverlay() {}
}
